package io.github.tanhoangkhoanguyen.vidstream.auth;

import static java.time.ZoneOffset.UTC;

import io.github.tanhoangkhoanguyen.vidstream.box.BoxHttp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * A user's Box access token for background work. Refreshes under a lock on the user's row: Box
 * rotates the refresh token on every refresh, so two nodes refreshing at once would lose it.
 */
@Service
@Profile("web")
public class TokenVault {

  private static final Logger log = LoggerFactory.getLogger(TokenVault.class);

  /** A token is refreshed once less than this is left. */
  static final Duration MIN_VALIDITY = Duration.ofMinutes(5);

  /** Box ends a refresh token unused for 60 days; idle ones are refreshed well before. */
  static final Duration IDLE = Duration.ofDays(30);

  private final JdbcClient jdbc;
  private final TokenCipher cipher;
  private final TransactionTemplate tx;
  private final ClientRegistration box;
  private final OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refresher;

  @Autowired
  TokenVault(
      JdbcClient jdbc,
      TokenCipher cipher,
      PlatformTransactionManager txManager,
      ClientRegistrationRepository registrations) {
    this(jdbc, cipher, txManager, registrations.findByRegistrationId("box"), refresher());
  }

  /** Spring's refresh client with the Box timeouts: a refresh runs under the user's row lock. */
  static RestClientRefreshTokenTokenResponseClient refresher() {
    var refresher = new RestClientRefreshTokenTokenResponseClient();
    refresher.setRestClient(
        RestClient.builder()
            .requestFactory(BoxHttp.requestFactory())
            .configureMessageConverters(
                c -> c.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build());
    return refresher;
  }

  TokenVault(
      JdbcClient jdbc,
      TokenCipher cipher,
      PlatformTransactionManager txManager,
      ClientRegistration box,
      OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refresher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.tx = new TransactionTemplate(txManager);
    this.box = box;
    this.refresher = refresher;
  }

  private record Stored(byte[] refresh, byte[] access, OffsetDateTime expiresAt) {}

  /**
   * A valid cached token is read without a lock, so concurrent jobs never queue on the user's row.
   * Only a refresh locks it, holding a DB connection during the call; refreshes are rare (about
   * once an hour). Other refresh errors (Box 5xx, network) are thrown and keep the stored tokens.
   *
   * @throws SignInRequiredException the user has no usable token; try another member
   */
  public String accessToken(long userId) {
    // On its own thread: interrupting the caller (a cancelled job) must not cut a refresh short
    // after Box rotated the refresh token, or the new one is lost. Committed before throwing, so a
    // rejected token is cleared for good.
    String token;
    try {
      token =
          CompletableFuture.supplyAsync(() -> cachedOrRefreshed(userId), Thread::startVirtualThread)
              .join(); // Not interruptible.
    } catch (CompletionException e) {
      throw e.getCause() instanceof RuntimeException cause ? cause : e;
    }
    if (token == null) throw new SignInRequiredException(userId);
    return token;
  }

  /**
   * Refreshes every grant idle for {@link #IDLE}, so workers keep a member token while users stay
   * offline. Only a revoked or expired grant needs the user to sign in again. Runs on every node;
   * the row lock and validity check make each refresh happen once.
   */
  @Scheduled(cron = "0 0 4 * * *", zone = "America/New_York")
  public void refreshIdle() {
    var idle =
        jdbc.sql(
                "SELECT id FROM users WHERE refresh_token_enc IS NOT NULL"
                    + " AND access_expires_at < now() - :idleMs * interval '1 millisecond'")
            .param("idleMs", IDLE.toMillis())
            .query(Long.class)
            .list();
    int failed = 0;
    for (long id : idle) {
      try {
        accessToken(id);
      } catch (SignInRequiredException e) {
        failed++; // Already cleared and logged.
      } catch (RuntimeException e) {
        failed++; // Box unreachable: tried again tomorrow, tokens kept.
      }
    }
    log.info("Idle Box tokens: {} refreshed, {} failed", idle.size() - failed, failed);
  }

  /** Box rejected the access token (401) before it expired: the next call refreshes it. */
  public void expire(long userId) {
    jdbc.sql(
            "UPDATE users SET access_expires_at = now()"
                + " WHERE id = ? AND access_token_enc IS NOT NULL")
        .param(userId)
        .update();
  }

  /** Box rejects the user's grant: they must sign in again. */
  public void clear(long userId) {
    jdbc.sql(
            "UPDATE users SET refresh_token_enc = NULL, access_token_enc = NULL,"
                + " access_expires_at = NULL WHERE id = ?")
        .param(userId)
        .update();
  }

  /** Null when the user must sign in again. */
  private String cachedOrRefreshed(long userId) {
    var stored = read(userId, "");
    if (isValid(stored)) return cipher.decrypt(stored.access());
    if (stored.refresh() == null) return null;
    return tx.execute(status -> lockedAccessToken(userId));
  }

  private Stored read(long userId, String lock) {
    return jdbc.sql(
            "SELECT refresh_token_enc, access_token_enc, access_expires_at FROM users WHERE id = ?"
                + lock)
        .param(userId)
        .query((rs, i) ->
            new Stored(rs.getBytes(1), rs.getBytes(2), rs.getObject(3, OffsetDateTime.class)))
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("no user " + userId));
  }

  private static boolean isValid(Stored stored) {
    return stored.access() != null
        && stored.expiresAt().isAfter(OffsetDateTime.now().plus(MIN_VALIDITY));
  }

  /**
   * Re-checked under the lock: another caller may have refreshed meanwhile. Null: sign in again.
   */
  private String lockedAccessToken(long userId) {
    var stored = read(userId, " FOR UPDATE");
    if (isValid(stored)) return cipher.decrypt(stored.access());
    if (stored.access() == null || stored.refresh() == null) return null;
    try {
      return refresh(userId, stored);
    } catch (OAuth2AuthorizationException e) {
      // invalid_grant: revoked, or unused for 60 days.
      if (!OAuth2ErrorCodes.INVALID_GRANT.equals(e.getError().getErrorCode())) throw e;
      log.warn("Box rejected the refresh token of user {}; tokens cleared", userId);
      clear(userId);
      return null;
    }
  }

  private String refresh(long userId, Stored stored) {
    var response =
        refresher.getTokenResponse(
            new OAuth2RefreshTokenGrantRequest(
                box,
                new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    cipher.decrypt(stored.access()),
                    null,
                    stored.expiresAt().toInstant()),
                new OAuth2RefreshToken(cipher.decrypt(stored.refresh()), null)));
    var access = response.getAccessToken();
    var refresh = response.getRefreshToken();
    jdbc.sql(
            "UPDATE users SET refresh_token_enc = ?, access_token_enc = ?, access_expires_at = ?"
                + " WHERE id = ?")
        .params(
            refresh == null ? stored.refresh() : cipher.encrypt(refresh.getTokenValue()),
            cipher.encrypt(access.getTokenValue()),
            OffsetDateTime.ofInstant(access.getExpiresAt(), UTC),
            userId)
        .update();
    return access.getTokenValue();
  }
}
