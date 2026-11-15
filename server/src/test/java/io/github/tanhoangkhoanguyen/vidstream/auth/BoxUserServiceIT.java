package io.github.tanhoangkhoanguyen.vidstream.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxProperties;
import io.github.tanhoangkhoanguyen.vidstream.dataset.DatasetAccess;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.client.RestClient;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class BoxUserServiceIT {

  static final ClientRegistration BOX =
      ClientRegistration.withRegistrationId("box")
          .clientId("test-client")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
          .authorizationUri("https://box.test/authorize")
          .tokenUri("https://box.test/token")
          .userInfoUri("https://box.test/users/me")
          .userNameAttributeName("id")
          .build();

  static final OAuth2UserRequest REQUEST =
      new OAuth2UserRequest(
          BOX,
          new OAuth2AccessToken(
              OAuth2AccessToken.TokenType.BEARER, "token", null, Instant.now().plusSeconds(3600)));

  @RegisterExtension
  static WireMockExtension box =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Autowired JdbcClient jdbc;
  BoxUserService service;

  @BeforeEach
  void setUp() {
    var client = new BoxClient(RestClient.builder(), new BoxProperties(URI.create(box.baseUrl())));
    var profile =
        new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
            Map.of("id", "42", "login", "a@usf.edu"),
            "id");
    service = new BoxUserService(request -> profile, new DatasetAccess(jdbc, client));
    dataset("a", "1", true);
    dataset("b", "2", true);
    dataset("c", "3", false);
  }

  void dataset(String name, String folder, boolean active) {
    jdbc.sql(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds, fps,"
                + " chunk_clips, active) VALUES (?, ?, 12, 5, 30, 50, ?)")
        .params(name, folder, active)
        .update();
  }

  void folder(String id, int status) {
    box.stubFor(get(urlPathEqualTo("/folders/" + id)).willReturn(aResponse().withStatus(status)));
  }

  @Test
  void grantsReadableActiveDatasets() {
    folder("1", 200);
    folder("2", 404);
    folder("3", 200);

    var user = service.loadUser(REQUEST);

    assertThat(user.getName()).isEqualTo("42");
    assertThat(user.<String>getAttribute("login")).isEqualTo("a@usf.edu");
    assertThat(user.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("OAUTH2_USER", "DATASET_a");
    box.verify(0, getRequestedFor(urlPathEqualTo("/folders/3")));
  }

  @Test
  void boxErrorFailsSignIn() {
    folder("1", 200);
    folder("2", 401);

    assertThatThrownBy(() -> service.loadUser(REQUEST))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
        .isEqualTo("box_unavailable");
  }
}
