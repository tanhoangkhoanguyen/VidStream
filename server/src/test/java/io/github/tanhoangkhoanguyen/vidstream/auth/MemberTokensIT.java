package io.github.tanhoangkhoanguyen.vidstream.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.tanhoangkhoanguyen.vidstream.Fixtures;
import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxProperties;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class MemberTokensIT {

  @RegisterExtension
  static WireMockExtension box =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager txManager;
  final TokenCipher cipher = new TokenCipher(new byte[32]);
  final AtomicInteger refreshes = new AtomicInteger();
  MemberTokens members;
  long dataset;

  @BeforeEach
  void setUp() {
    var registration =
        ClientRegistration.withRegistrationId("box")
            .clientId("test-client")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://box.test/authorize")
            .tokenUri("https://box.test/token")
            .build();
    var vault =
        new TokenVault(
            jdbc,
            cipher,
            txManager,
            registration,
            request -> {
              refreshes.incrementAndGet();
              return OAuth2AccessTokenResponse.withToken("fresh")
                  .tokenType(OAuth2AccessToken.TokenType.BEARER)
                  .expiresIn(3600)
                  .build();
            });
    var client = new BoxClient(RestClient.builder(), new BoxProperties(URI.create(box.baseUrl())));
    members = new MemberTokens(jdbc, vault, client);
    dataset =
        jdbc.sql(
                "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                    + " fps, chunk_clips) VALUES ('d', '1', 12, 5, 30, 50) RETURNING id")
            .query(Long.class)
            .single();
  }

  /** A member with a cached access token; null: no usable token. */
  long member(String token) {
    var sql =
        token == null
            ? "INSERT INTO users (box_user_id, login) VALUES (gen_random_uuid(), 'u') RETURNING id"
            : "INSERT INTO users (box_user_id, login, refresh_token_enc, access_token_enc,"
                + " access_expires_at) VALUES (gen_random_uuid(), 'u', :refresh, :access,"
                + " :expires) RETURNING id";
    var insert = jdbc.sql(sql);
    if (token != null)
      insert
          .param("refresh", cipher.encrypt("refresh"))
          .param("access", cipher.encrypt(token))
          .param("expires", OffsetDateTime.now().plusHours(1));
    long user = insert.query(Long.class).single();
    jdbc.sql("INSERT INTO dataset_members (user_id, dataset_id) VALUES (?, ?)")
        .params(user, dataset)
        .update();
    return user;
  }

  static HttpClientErrorException error(HttpStatus status) {
    return HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, null, null);
  }

  void folderAnswers(String token, int status) {
    box.stubFor(
        get(urlPathEqualTo("/folders/1"))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(
                aResponse().withStatus(status).withBody(Fixtures.read("box/error-404.json"))));
  }

  @Test
  void skipsMemberWithoutUsableToken() {
    member(null);
    member("b");

    String used = members.call(dataset, token -> token);

    assertThat(used).isEqualTo("b");
  }

  @Test
  void refreshesOnceAfter401() {
    member("old");

    var used =
        members.call(
            dataset,
            token -> {
              if (token.equals("old")) throw error(HttpStatus.UNAUTHORIZED);
              return token;
            });

    assertThat(used).isEqualTo("fresh");
    assertThat(refreshes).hasValue(1);
  }

  @Test
  void clearsMemberRejectedAfterRefresh() {
    long a = member("old");
    member("b");

    var used =
        members.call(
            dataset,
            token -> {
              if (!token.equals("b")) throw error(HttpStatus.UNAUTHORIZED);
              return token;
            });

    assertThat(used).isEqualTo("b");
    assertThat(refreshes).hasValue(1);
    assertThat(
            jdbc.sql("SELECT refresh_token_enc IS NULL FROM users WHERE id = ?")
                .param(a)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void removesMemberWhoLostAccess() {
    member("a");
    long b = member("b");
    folderAnswers("a", 404);

    var used =
        members.call(
            dataset,
            token -> {
              if (token.equals("a")) throw error(HttpStatus.NOT_FOUND);
              return token;
            });

    assertThat(used).isEqualTo("b");
    assertThat(jdbc.sql("SELECT user_id FROM dataset_members").query(Long.class).list())
        .containsExactly(b);
  }

  @Test
  void missingTargetIsThrownAndMemberKept() {
    member("a");
    folderAnswers("a", 200);

    assertThatThrownBy(() ->
            members.call(
                dataset,
                token -> {
                  throw error(HttpStatus.NOT_FOUND);
                }))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThat(jdbc.sql("SELECT count(*) FROM dataset_members").query(Long.class).single())
        .isEqualTo(1);
  }

  @Test
  void noUsableMemberIsThrown() {
    member(null);

    assertThatThrownBy(() -> members.<String>call(dataset, token -> token))
        .isInstanceOf(NoUsableMemberException.class);
  }
}
