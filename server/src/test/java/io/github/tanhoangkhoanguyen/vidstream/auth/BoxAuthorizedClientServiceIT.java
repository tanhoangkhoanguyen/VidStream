package io.github.tanhoangkhoanguyen.vidstream.auth;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class BoxAuthorizedClientServiceIT {

  static final ClientRegistration BOX =
      ClientRegistration.withRegistrationId("box")
          .clientId("test-client")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
          .authorizationUri("https://box.test/authorize")
          .tokenUri("https://box.test/token")
          .build();

  @Autowired JdbcClient jdbc;
  BoxAuthorizedClientService service;

  @BeforeEach
  void setUp() {
    service =
        new BoxAuthorizedClientService(
            jdbc, new TokenCipher(new byte[32]), new InMemoryClientRegistrationRepository(BOX));
  }

  void signIn(String login, String access, String refresh, Instant expires, String... datasets) {
    var authorities = Stream.of(datasets).map(DatasetAuthorities::of).toList();
    var user = new DefaultOAuth2User(authorities, Map.of("id", "42", "login", login), "id");
    var principal = new OAuth2AuthenticationToken(user, authorities, "box");
    var client =
        new OAuth2AuthorizedClient(
            BOX,
            "42",
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, access, null, expires),
            new OAuth2RefreshToken(refresh, null));
    service.saveAuthorizedClient(client, principal);
  }

  @Test
  void signInStoresUserWithEncryptedTokens() {
    var expires = Instant.now().plusSeconds(3600).truncatedTo(SECONDS);
    signIn("a@usf.edu", "access-1", "refresh-1", expires);

    var stored =
        jdbc.sql("SELECT login, refresh_token_enc FROM users WHERE box_user_id = '42'")
            .query((rs, i) -> rs.getString(1) + ":" + new String(rs.getBytes(2)))
            .single();
    assertThat(stored).startsWith("a@usf.edu:").doesNotContain("refresh-1");

    OAuth2AuthorizedClient loaded = service.loadAuthorizedClient("box", "42");
    assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo("access-1");
    assertThat(loaded.getAccessToken().getExpiresAt()).isEqualTo(expires);
    assertThat(loaded.getRefreshToken().getTokenValue()).isEqualTo("refresh-1");
  }

  @Test
  void signInAgainUpdatesTheSameUser() {
    signIn("a@usf.edu", "access-1", "refresh-1", Instant.now().plusSeconds(3600));
    signIn("new@usf.edu", "access-2", "refresh-2", Instant.now().plusSeconds(3600));

    assertThat(jdbc.sql("SELECT login FROM users").query(String.class).list())
        .containsExactly("new@usf.edu");
    OAuth2AuthorizedClient loaded = service.loadAuthorizedClient("box", "42");
    assertThat(loaded.getRefreshToken().getTokenValue()).isEqualTo("refresh-2");
  }

  void dataset(String name, String folder) {
    jdbc.sql(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds, fps,"
                + " chunk_clips) VALUES (?, ?, 12, 5, 30, 50)")
        .params(name, folder)
        .update();
  }

  List<String> members() {
    return jdbc.sql(
            "SELECT d.name FROM dataset_members m JOIN datasets d ON d.id = m.dataset_id"
                + " ORDER BY d.name")
        .query(String.class)
        .list();
  }

  @Test
  void signInReplacesActiveMemberships() {
    dataset("a", "1");
    dataset("b", "2");
    dataset("c", "3");
    var expires = Instant.now().plusSeconds(3600);

    signIn("a@usf.edu", "access-1", "refresh-1", expires, "b", "c");
    assertThat(members()).containsExactly("b", "c");

    // Lost b, gained a; c went inactive, so it was not checked and stays.
    jdbc.sql("UPDATE datasets SET active = false WHERE name = 'c'").update();
    signIn("a@usf.edu", "access-2", "refresh-2", expires, "a");
    assertThat(members()).containsExactly("a", "c");

    signIn("a@usf.edu", "access-3", "refresh-3", expires);
    assertThat(members()).containsExactly("c");
  }

  @Test
  void removeClearsTokensButKeepsUser() {
    signIn("a@usf.edu", "access-1", "refresh-1", Instant.now().plusSeconds(3600));
    service.removeAuthorizedClient("box", "42");

    assertThat((OAuth2AuthorizedClient) service.loadAuthorizedClient("box", "42")).isNull();
    assertThat(jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single()).isOne();
  }
}
