package io.github.tanhoangkhoanguyen.vidstream.auth;

import static java.time.ZoneOffset.UTC;

import io.github.tanhoangkhoanguyen.vidstream.box.BoxHttp;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
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
public class TokenVault {

  private static final Logger log = LoggerFactory.getLogger(TokenVault.class);

  /** A token is refreshed once less than this is left. */
  static final Duration MIN_VALIDITY = Duration.ofMinutes(5);

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
   * Holds a DB connection during the refresh call; refreshes are rare (about once an hour). Other
   * refresh errors (Box 5xx, network) are thrown and keep the stored tokens.
   *
   * @throws SignInRequiredException the user has no usable token; try another member
   */
  public String accessToken(long userId) {
    // Committed before throwing, so a rejected token is cleared for good.
    var token = tx.execute(status -> lockedAccessToken(userId));
    if (token == null) throw new SignInRequiredException(userId);
    return token;
  }

  /** Null when the user must sign in again. */
  private String lockedAccessToken(long userId) {
    var stored =
        jdbc.sql(
                "SELECT refresh_token_enc, access_token_enc, access_expires_at FROM users"
                    + " WHERE id = ? FOR UPDATE")
            .param(userId)
            .query((rs, i) ->
                new Stored(rs.getBytes(1), rs.getBytes(2), rs.getObject(3, OffsetDateTime.class)))
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("no user " + userId));
    if (stored.access() == null) return null;
    if (stored.expiresAt().isAfter(OffsetDateTime.now().plus(MIN_VALIDITY)))
      return cipher.decrypt(stored.access());
    if (stored.refresh() == null) return null;
    try {
      return refresh(userId, stored);
    } catch (OAuth2AuthorizationException e) {
      // invalid_grant: revoked, or unused for 60 days.
      if (!OAuth2ErrorCodes.INVALID_GRANT.equals(e.getError().getErrorCode())) throw e;
      log.warn("Box rejected the refresh token of user {}; tokens cleared", userId);
      jdbc.sql(
              "UPDATE users SET refresh_token_enc = NULL, access_token_enc = NULL,"
                  + " access_expires_at = NULL WHERE id = ?")
          .param(userId)
          .update();
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
