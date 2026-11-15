package io.github.tanhoangkhoanguyen.vidstream.box;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.tanhoangkhoanguyen.vidstream.Fixtures;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class BoxClientTest {

  @RegisterExtension
  static WireMockExtension box =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  BoxClient client;

  @BeforeEach
  void setUp() {
    client =
        new BoxClient(RestClient.builder(), new BoxProperties(URI.create(box.baseUrl() + "/2.0")));
  }

  @Test
  void canAccessFolder() {
    box.stubFor(get(urlPathEqualTo("/2.0/folders/1")).willReturn(aResponse().withStatus(200)));

    assertThat(client.canAccess("token", "1")).isTrue();
    box.verify(
        getRequestedFor(urlPathEqualTo("/2.0/folders/1"))
            .withQueryParam("fields", equalTo("id"))
            .withHeader("Authorization", equalTo("Bearer token")));
  }

  @Test
  void noAccessIs404() {
    box.stubFor(
        get(urlPathEqualTo("/2.0/folders/1"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(Fixtures.read("box/error-404.json"))));

    assertThat(client.canAccess("token", "1")).isFalse();
  }

  @Test
  void serverErrorThrows() {
    box.stubFor(get(urlPathEqualTo("/2.0/folders/1")).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> client.canAccess("token", "1"))
        .isInstanceOf(HttpServerErrorException.class);
  }
}
