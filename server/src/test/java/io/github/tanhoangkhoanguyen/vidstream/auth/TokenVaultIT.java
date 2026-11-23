package io.github.tanhoangkhoanguyen.vidstream.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.jsonResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// Not rolled back: the threads need committed rows. Cleaned up after each test.
@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TokenVaultIT {

  static final String REFRESHED =
      """
      {"access_token": "access-2", "expires_in": 3600, "restricted_to": [],
       "refresh_token": "refresh-2", "token_type": "bearer"}\
      """;

  @RegisterExtension
  static WireMockExtension box =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager txManager;
  final TokenCipher cipher = new TokenCipher(new byte[32]);
  TokenVault vault;

  @BeforeEach
  void setUp() {
    var registration =
        ClientRegistration.withRegistrationId("box")
            .clientId("test-client")
            .clientSecret("test-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://box.test/authorize")
            .tokenUri(box.baseUrl() + "/token")
            .build();
    vault = new TokenVault(jdbc, cipher, txManager, registration, TokenVault.refresher());
    // Slow enough that waiting threads pile up on the row lock.
    box.stubFor(post("/token").willReturn(okJson(REFRESHED).withFixedDelay(200)));
  }

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM users").update();
  }

  long user(Duration expiresIn) {
    return jdbc.sql(
            "INSERT INTO users (box_user_id, login, refresh_token_enc, access_token_enc,"
                + " access_expires_at) VALUES ('42', 'a@usf.edu', ?, ?, ?) RETURNING id")
        .params(
            cipher.encrypt("refresh-1"),
            cipher.encrypt("access-1"),
            OffsetDateTime.now().plus(expiresIn))
        .query(Long.class)
        .single();
  }

  @Test
  void validTokenIsNotRefreshed() {
    long id = user(Duration.ofHours(1));

    assertThat(vault.accessToken(id)).isEqualTo("access-1");
    box.verify(0, postRequestedFor(urlPathEqualTo("/token")));
  }

  @Test
  void validTokenIsReadWithoutTheRowLock() throws Exception {
    long id = user(Duration.ofHours(1));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var tx = new TransactionTemplate(txManager);
    // Another caller holds the row lock, as during a refresh.
    var holder =
        Thread.ofVirtual()
            .start(() ->
                tx.executeWithoutResult(status -> {
                  jdbc.sql("SELECT id FROM users WHERE id = ? FOR UPDATE")
                      .param(id)
                      .query()
                      .rowSet();
                  locked.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
    assertThat(locked.await(5, SECONDS)).isTrue();

    try {
      assertThat(CompletableFuture.supplyAsync(() -> vault.accessToken(id)).get(2, SECONDS))
          .isEqualTo("access-1");
    } finally {
      release.countDown();
      holder.join();
    }
  }

  @Test
  void twentyThreadsRefreshOnce() throws Exception {
    // Not expired yet, but under the 5-minute margin.
    long id = user(Duration.ofMinutes(2));

    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var calls =
          IntStream.range(0, 20)
              .mapToObj(i -> (Callable<String>) () -> vault.accessToken(id))
              .toList();
      assertThat(pool.invokeAll(calls)).map(Future::resultNow).containsOnly("access-2");
    }

    box.verify(
        1,
        postRequestedFor(urlPathEqualTo("/token"))
            .withRequestBody(containing("grant_type=refresh_token"))
            .withRequestBody(containing("refresh_token=refresh-1"))
            .withRequestBody(containing("client_secret=test-secret")));
    var refresh =
        jdbc.sql("SELECT refresh_token_enc FROM users WHERE id = ?")
            .param(id)
            .query(byte[].class)
            .single();
    assertThat(cipher.decrypt(refresh)).isEqualTo("refresh-2");
  }

  @Test
  void interruptedCallerKeepsRotatedToken() throws Exception {
    long id = user(Duration.ZERO);
    var result = new CompletableFuture<String>();

    // A cancelled job: its thread is interrupted while it asks for a token.
    Thread.ofVirtual()
        .start(() -> {
          Thread.currentThread().interrupt();
          result.complete(vault.accessToken(id) + " " + Thread.currentThread().isInterrupted());
        });

    assertThat(result.get(5, SECONDS)).isEqualTo("access-2 true");
    var refresh =
        jdbc.sql("SELECT refresh_token_enc FROM users WHERE id = ?")
            .param(id)
            .query(byte[].class)
            .single();
    assertThat(cipher.decrypt(refresh)).isEqualTo("refresh-2");
  }

  boolean hasTokens(long id) {
    return jdbc.sql("SELECT refresh_token_enc IS NOT NULL FROM users WHERE id = ?")
        .param(id)
        .query(Boolean.class)
        .single();
  }

  @Test
  void rejectedRefreshTokenIsCleared() {
    long id = user(Duration.ZERO);
    box.stubFor(
        post("/token")
            .willReturn(
                jsonResponse(
                    """
                    {"error": "invalid_grant", "error_description": "Refresh token has expired"}\
                    """,
                    400)));

    assertThatThrownBy(() -> vault.accessToken(id)).isInstanceOf(SignInRequiredException.class);
    assertThat(hasTokens(id)).isFalse();
    // Cleared: the next call fails without asking Box.
    assertThatThrownBy(() -> vault.accessToken(id)).isInstanceOf(SignInRequiredException.class);
    box.verify(1, postRequestedFor(urlPathEqualTo("/token")));
  }

  @Test
  void boxErrorKeepsTokens() {
    long id = user(Duration.ZERO);
    box.stubFor(post("/token").willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> vault.accessToken(id))
        .isInstanceOf(OAuth2AuthorizationException.class);
    assertThat(hasTokens(id)).isTrue();
  }

  @Test
  void idleGrantIsRefreshed() {
    long id = user(TokenVault.IDLE.negated().minusDays(1));

    vault.refreshIdle();

    box.verify(1, postRequestedFor(urlPathEqualTo("/token")));
    assertThat(vault.accessToken(id)).isEqualTo("access-2");
  }

  @Test
  void recentGrantIsLeftAlone() {
    user(Duration.ofMinutes(-1));

    vault.refreshIdle();

    box.verify(0, postRequestedFor(urlPathEqualTo("/token")));
  }
}
