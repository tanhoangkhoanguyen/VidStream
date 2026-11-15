package io.github.tanhoangkhoanguyen.vidstream.auth;

import static java.time.ZoneOffset.UTC;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A user's Box access token for background work. Refreshes under a lock on the user's row: Box
 * rotates the refresh token on every refresh, so two nodes refreshing at once would lose it.
 */
@Service
public class TokenVault {

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
    this(
        jdbc,
        cipher,
        txManager,
        registrations.findByRegistrationId("box"),
        new RestClientRefreshTokenTokenResponseClient());
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

  /** Holds a DB connection during the refresh call; refreshes are rare (about once an hour). */
  public String accessToken(long userId) {
    return tx.execute(status -> {
      var stored =
          jdbc.sql(
                  "SELECT refresh_token_enc, access_token_enc, access_expires_at FROM users"
                      + " WHERE id = ? FOR UPDATE")
              .param(userId)
              .query((rs, i) ->
                  new Stored(rs.getBytes(1), rs.getBytes(2), rs.getObject(3, OffsetDateTime.class)))
              .optional()
              .orElseThrow(() -> new IllegalArgumentException("no user " + userId));
      if (stored.access() == null) throw new IllegalStateException("user has no Box token");
      if (stored.expiresAt().isAfter(OffsetDateTime.now().plus(MIN_VALIDITY)))
        return cipher.decrypt(stored.access());
      if (stored.refresh() == null) throw new IllegalStateException("user has no refresh token");
      return refresh(userId, stored);
    });
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
