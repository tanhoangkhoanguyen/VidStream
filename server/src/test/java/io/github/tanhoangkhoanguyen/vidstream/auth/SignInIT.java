package io.github.tanhoangkhoanguyen.vidstream.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.util.UriComponentsBuilder;

/** The whole sign-in against WireMock as Box: OAuth2 code flow, user row, membership, gate. */
@SpringBootTest(
    properties = {"vidstream.role=web", "vidstream.datasets-file=classpath:datasets.yml"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class SignInIT {

  // Shapes from the Box API docs; values made up.
  static final String TOKEN =
      """
      {"access_token": "access-1", "expires_in": 3600, "restricted_to": [],
       "refresh_token": "refresh-1", "token_type": "bearer"}\
      """;
  static final String ME =
      """
      {"type": "user", "id": "42", "name": "Test User", "login": "test@example.edu"}\
      """;

  @RegisterExtension
  static WireMockExtension box =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void boxUrls(DynamicPropertyRegistry registry) {
    var provider = "spring.security.oauth2.client.provider.box.";
    registry.add(provider + "token-uri", () -> box.baseUrl() + "/oauth2/token");
    registry.add(provider + "user-info-uri", () -> box.baseUrl() + "/2.0/users/me");
    registry.add("vidstream.box.api-url", () -> box.baseUrl() + "/2.0");
  }

  @Autowired MockMvcTester mvc;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    box.stubFor(post("/oauth2/token").willReturn(okJson(TOKEN)));
    box.stubFor(get("/2.0/users/me").willReturn(okJson(ME)));
    box.stubFor(get("/2.0/folders/1?fields=id").willReturn(aResponse().withStatus(200)));
    box.stubFor(get("/2.0/folders/2?fields=id").willReturn(aResponse().withStatus(404)));
    jdbc.sql(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds, fps,"
                + " chunk_clips) VALUES ('a', '1', 12, 5, 30, 50), ('b', '2', 12, 5, 30, 50)")
        .update();
  }

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM dataset_members").update();
    jdbc.sql("DELETE FROM users").update();
    jdbc.sql("DELETE FROM datasets").update();
  }

  void signIn(MockHttpSession session) {
    var consent = mvc.get().uri("/oauth2/authorization/box").session(session).exchange();
    // Still URL-encoded, as Box would send it back.
    var state =
        UriComponentsBuilder.fromUriString(consent.getResponse().getRedirectedUrl())
            .build()
            .getQueryParams()
            .getFirst("state");
    assertThat(
            mvc.get()
                .uri(URI.create("/login/oauth2/code/box?code=the-code&state=" + state))
                .session(session))
        .hasStatus(HttpStatus.FOUND)
        .hasRedirectedUrl("/");
  }

  MockMvcTester.MockMvcRequestBuilder gate(String dataset, MockHttpSession session) {
    return mvc.get().uri("https://" + dataset + ".vidstream.test/internal/gate").session(session);
  }

  @Test
  void signInStoresUserAndMembershipAndOpensTheGate() {
    var session = new MockHttpSession();
    signIn(session);

    assertThat(
            jdbc.sql("SELECT login FROM users WHERE box_user_id = '42'")
                .query(String.class)
                .single())
        .isEqualTo("test@example.edu");
    assertThat(
            jdbc.sql("SELECT d.name FROM dataset_members m JOIN datasets d ON d.id = m.dataset_id")
                .query(String.class)
                .list())
        .containsExactly("a");
    assertThat(gate("a", session)).hasStatus(HttpStatus.OK);
    assertThat(gate("b", session)).hasStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void logoutClosesTheGate() {
    var session = new MockHttpSession();
    signIn(session);

    assertThat(mvc.post().uri("/logout").with(csrf()).session(session)).hasStatus(HttpStatus.FOUND);
    assertThat(session.isInvalid()).isTrue();
    assertThat(gate("a", new MockHttpSession())).hasStatus(HttpStatus.UNAUTHORIZED);
  }
}
