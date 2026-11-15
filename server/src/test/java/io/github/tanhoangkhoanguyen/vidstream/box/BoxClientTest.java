package io.github.tanhoangkhoanguyen.vidstream.box;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.tanhoangkhoanguyen.vidstream.Fixtures;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class BoxClientTest {

  static final String FOLDER = "/2.0/folders/1";

  @RegisterExtension
  static WireMockExtension box =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  final List<Duration> waits = new ArrayList<>();
  BoxClient client;

  @BeforeEach
  void setUp() {
    var props = new BoxProperties(URI.create(box.baseUrl() + "/2.0"));
    client = new BoxClient(RestClient.builder(), props, waits::add);
  }

  /** Answers with the given responses in order, then repeats the last one. */
  void respond(ResponseDefinitionBuilder... responses) {
    int last = responses.length - 1;
    for (int i = 0; i <= last; i++) {
      box.stubFor(
          get(urlPathEqualTo(FOLDER))
              .inScenario("calls")
              .whenScenarioStateIs(state(i))
              .willSetStateTo(state(Math.min(i + 1, last)))
              .willReturn(responses[i]));
    }
  }

  static String state(int call) {
    return call == 0 ? STARTED : "call " + call;
  }

  @Test
  void canAccessFolder() {
    respond(aResponse().withStatus(200));

    assertThat(client.canAccess("token", "1")).isTrue();
    box.verify(
        getRequestedFor(urlPathEqualTo(FOLDER))
            .withQueryParam("fields", equalTo("id"))
            .withHeader("Authorization", equalTo("Bearer token")));
  }

  @Test
  void noAccessIs404AndNotRetried() {
    respond(
        aResponse()
            .withStatus(404)
            .withHeader("Content-Type", "application/json")
            .withBody(Fixtures.read("box/error-404.json")));

    assertThat(client.canAccess("token", "1")).isFalse();
    box.verify(1, getRequestedFor(urlPathEqualTo(FOLDER)));
  }

  @Test
  void waitsRetryAfterOn429() {
    respond(
        aResponse().withStatus(429).withHeader("Retry-After", "2"), aResponse().withStatus(200));

    assertThat(client.canAccess("token", "1")).isTrue();
    assertThat(waits).containsExactly(Duration.ofSeconds(2));
  }

  @Test
  void retries5xxWithBackoff() {
    respond(aResponse().withStatus(503), aResponse().withStatus(502), aResponse().withStatus(200));

    assertThat(client.canAccess("token", "1")).isTrue();
    assertThat(waits)
        .satisfiesExactly(
            first -> assertThat(first).isBetween(Duration.ofMillis(250), Duration.ofMillis(500)),
            second ->
                assertThat(second).isBetween(Duration.ofMillis(500), Duration.ofMillis(1000)));
  }

  @Test
  void givesUpAfterThreeRetries() {
    respond(aResponse().withStatus(500));

    assertThatThrownBy(() -> client.canAccess("token", "1"))
        .isInstanceOf(HttpServerErrorException.class);
    box.verify(1 + BoxRetry.MAX_RETRIES, getRequestedFor(urlPathEqualTo(FOLDER)));
    assertThat(waits).hasSize(BoxRetry.MAX_RETRIES);
  }
}
