package io.github.tanhoangkhoanguyen.vidstream.box;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class BoxHttpTest {

  @RegisterExtension
  static WireMockExtension box =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  final RestClient http =
      RestClient.builder()
          .baseUrl(box.baseUrl())
          .requestFactory(BoxHttp.requestFactory(Duration.ofSeconds(1), Duration.ofMillis(200)))
          .build();

  @Test
  void hungCallTimesOut() {
    box.stubFor(get("/slow").willReturn(aResponse().withFixedDelay(2_000)));

    assertThatThrownBy(() -> http.get().uri("/slow").retrieve().toBodilessEntity())
        .isInstanceOf(ResourceAccessException.class);
  }

  @Test
  void redirectIsNotFollowed() {
    box.stubFor(
        get("/content").willReturn(aResponse().withStatus(302).withHeader("Location", "/x")));

    var response = http.get().uri("/content").retrieve().toBodilessEntity();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
  }
}
