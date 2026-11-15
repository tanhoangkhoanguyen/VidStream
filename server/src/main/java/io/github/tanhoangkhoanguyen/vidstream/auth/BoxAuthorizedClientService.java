package io.github.tanhoangkhoanguyen.vidstream.auth;

import static java.time.ZoneOffset.UTC;

import java.time.OffsetDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps each user's Box tokens, encrypted, in {@code users}: sign-in upserts the user and their
 * {@code dataset_members}. Workers use a member's tokens while the user is offline.
 */
@Service
@Profile("web")
class BoxAuthorizedClientService implements OAuth2AuthorizedClientService {

  private final JdbcClient jdbc;
  private final TokenCipher cipher;
  private final ClientRegistrationRepository registrations;

  BoxAuthorizedClientService(
      JdbcClient jdbc, TokenCipher cipher, ClientRegistrationRepository registrations) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.registrations = registrations;
  }

  @Override
  @Transactional
  public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal) {
    var user = (OAuth2User) principal.getPrincipal();
    var access = client.getAccessToken();
    var refresh = client.getRefreshToken();
    long userId =
        jdbc.sql(
                """
                INSERT INTO users (box_user_id, login, refresh_token_enc, access_token_enc, access_expires_at)
                VALUES (:id, :login, :refresh, :access, :expires)
                ON CONFLICT (box_user_id) DO UPDATE SET
                  login = EXCLUDED.login,
                  refresh_token_enc = EXCLUDED.refresh_token_enc,
                  access_token_enc = EXCLUDED.access_token_enc,
                  access_expires_at = EXCLUDED.access_expires_at
                RETURNING id\
                """)
            .param("id", principal.getName())
            .param("login", user.<String>getAttribute("login"))
            .param("refresh", refresh == null ? null : cipher.encrypt(refresh.getTokenValue()))
            .param("access", cipher.encrypt(access.getTokenValue()))
            .param("expires", OffsetDateTime.ofInstant(access.getExpiresAt(), UTC))
            .query(Long.class)
            .single();
    saveMembership(userId, principal);
  }

  /** Active datasets match the sign-in check; inactive ones were not checked, so they stay. */
  private void saveMembership(long userId, Authentication principal) {
    var datasets = DatasetAuthorities.names(principal).toArray(String[]::new);
    jdbc.sql(
            """
            DELETE FROM dataset_members m USING datasets d
            WHERE m.user_id = :user AND m.dataset_id = d.id AND d.active AND d.name <> ALL(:names)\
            """)
        .param("user", userId)
        .param("names", datasets)
        .update();
    jdbc.sql(
            """
            INSERT INTO dataset_members (user_id, dataset_id)
            SELECT :user, id FROM datasets WHERE name = ANY(:names)
            ON CONFLICT DO NOTHING\
            """)
        .param("user", userId)
        .param("names", datasets)
        .update();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
      String registrationId, String principalName) {
    var registration = registrations.findByRegistrationId(registrationId);
    return jdbc.sql(
            "SELECT refresh_token_enc, access_token_enc, access_expires_at FROM users"
                + " WHERE box_user_id = ? AND access_token_enc IS NOT NULL")
        .param(principalName)
        .query((rs, i) -> {
          var access =
              new OAuth2AccessToken(
                  OAuth2AccessToken.TokenType.BEARER,
                  cipher.decrypt(rs.getBytes(2)),
                  null,
                  rs.getObject(3, OffsetDateTime.class).toInstant());
          var refreshEnc = rs.getBytes(1);
          var refresh =
              refreshEnc == null ? null : new OAuth2RefreshToken(cipher.decrypt(refreshEnc), null);
          return (T) new OAuth2AuthorizedClient(registration, principalName, access, refresh);
        })
        .optional()
        .orElse(null);
  }

  @Override
  public void removeAuthorizedClient(String registrationId, String principalName) {
    jdbc.sql(
            "UPDATE users SET refresh_token_enc = NULL, access_token_enc = NULL,"
                + " access_expires_at = NULL WHERE box_user_id = ?")
        .param(principalName)
        .update();
  }
}
