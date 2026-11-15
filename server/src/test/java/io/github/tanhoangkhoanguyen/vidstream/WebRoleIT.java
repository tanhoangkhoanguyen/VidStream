package io.github.tanhoangkhoanguyen.vidstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "vidstream.role=web",
      "vidstream.datasets-file=classpath:datasets.yml",
      "management.server.port=0"
    })
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class WebRoleIT {

  @Autowired MockMvcTester mvc;

  @LocalServerPort int port;

  @LocalManagementPort int managementPort;

  RestTestClient client(int port) {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void managementExposesOnlyHealthInfoPrometheus() {
    var management = client(managementPort);
    management
        .get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
    management.get().uri("/actuator/info").exchange().expectStatus().isOk();
    management.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    // Not exposed: falls through to sign-in.
    management
        .get()
        .uri("/actuator/env")
        .exchange()
        .expectStatus()
        .value(status -> assertThat(status).isNotEqualTo(200));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", "/actuator/health"})
  void anonymousIsSentToBoxSignIn(String path) {
    client(port)
        .get()
        .uri(path)
        .exchange()
        .expectStatus()
        .isFound()
        .expectHeader()
        .location("http://localhost:" + port + "/oauth2/authorization/box");
  }

  @Test
  void signInRedirectsToBoxConsent() {
    client(port)
        .get()
        .uri("/oauth2/authorization/box")
        .exchange()
        .expectStatus()
        .isFound()
        .expectHeader()
        .value(
            "Location",
            location ->
                assertThat(location)
                    .startsWith("https://account.box.com/api/oauth2/authorize?")
                    .contains("client_id=test-client", "state=")
                    .contains("redirect_uri=http://localhost:" + port + "/login/oauth2/code/box"))
        .expectHeader()
        .value(
            "Set-Cookie",
            cookie ->
                assertThat(cookie)
                    .startsWith("vs_session=")
                    .contains(
                        "Max-Age=28800",
                        "Domain=vidstream.test",
                        "Secure",
                        "HttpOnly",
                        "SameSite=Lax"));
  }

  @Test
  void logoutNeedsCsrf() {
    assertThat(mvc.post().uri("/logout").with(oauth2Login())).hasStatus(HttpStatus.FORBIDDEN);
  }

  MockMvcTester.MockMvcRequestBuilder gate(String host) {
    return mvc.get().uri("https://" + host + "/internal/gate");
  }

  @Test
  void gateLetsMembersIntoTheirDatasetsOnly() {
    var member = oauth2Login().authorities(new SimpleGrantedAuthority("DATASET_a"));
    assertThat(gate("a.vidstream.test").with(member)).hasStatus(HttpStatus.OK);
    assertThat(gate("A.Vidstream.Test").with(member)).hasStatus(HttpStatus.OK);
    assertThat(gate("b.vidstream.test").with(member)).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(gate("a.other.test").with(member)).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(gate("vidstream.test").with(member)).hasStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void gateRejectsNoSession() {
    assertThat(gate("a.vidstream.test")).hasStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void logoutClearsSessionCookie() {
    assertThat(mvc.post().uri("/logout").with(oauth2Login()).with(csrf()))
        .hasStatus(HttpStatus.FOUND)
        .cookies()
        .hasMaxAge("vs_session", Duration.ZERO);
  }
}
