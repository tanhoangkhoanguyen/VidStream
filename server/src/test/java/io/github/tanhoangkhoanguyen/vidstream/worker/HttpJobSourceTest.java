package io.github.tanhoangkhoanguyen.vidstream.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.queue.StaleLeaseException;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The wire format WorkerApiTest maps; the server is stubbed. */
class HttpJobSourceTest {

  // No h2c: the JDK client's plain-HTTP upgrade breaks WireMock; the real API is HTTPS.
  @RegisterExtension
  static WireMockExtension server =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
          .build();

  static final Job JOB = new Job(3, Type.CHUNK, 1, 2L, "{}", 5);

  final HttpJobSource source = new HttpJobSource(URI.create(server.baseUrl()), "vsw_t");

  @Test
  void claimSendsTheSlotWithTheToken() {
    server.stubFor(
        post("/api/worker/claim")
            .withHeader("Authorization", equalTo("Bearer vsw_t"))
            .withRequestBody(equalToJson("{\"slot\": \"pc-0\", \"types\": [\"CHUNK\"]}"))
            .willReturn(
                okJson(
                    """
                    {"job": {"id": 3, "type": "CHUNK", "datasetId": 1, "videoId": 2,
                             "payload": "{}", "leaseVersion": 5},
                     "work": {"fps": 30}}
                    """)));

    var assignment = source.claim("pc-0", Set.of(Type.CHUNK)).orElseThrow();

    assertThat(assignment.job()).isEqualTo(JOB);
    assertThat(assignment.work().get("fps").asInt()).isEqualTo(30);
  }

  @Test
  void noJobWithinTheLongPollIsEmpty() {
    server.stubFor(post("/api/worker/claim").willReturn(aResponse().withStatus(204)));

    assertThat(source.claim("pc-0", Set.of(Type.CHUNK))).isEmpty();
  }

  @Test
  void reportsCarryTheLeaseVersion() {
    server.stubFor(post("/api/worker/jobs/3/release").willReturn(aResponse().withStatus(204)));
    server.stubFor(
        post("/api/worker/jobs/3/uploads")
            .willReturn(okJson("[{\"url\": \"https://s3.test/k\", \"headers\": {\"h\": \"v\"}}]")));

    source.release(JOB, Duration.ofSeconds(1));
    var uploads = source.uploads(JOB, List.of(new Blob(0, 3, "ab")));

    server.verify(
        postRequestedFor(urlEqualTo("/api/worker/jobs/3/release"))
            .withRequestBody(equalToJson("{\"leaseVersion\": 5, \"delayMs\": 1000}")));
    server.verify(
        postRequestedFor(urlEqualTo("/api/worker/jobs/3/uploads"))
            .withRequestBody(
                equalToJson(
                    "{\"leaseVersion\": 5,"
                        + " \"blobs\": [{\"index\": 0, \"size\": 3, \"sha256\": \"ab\"}]}")));
    assertThat(uploads)
        .containsExactly(new Upload(URI.create("https://s3.test/k"), Map.of("h", "v")));
  }

  @Test
  void redirectIsAnError() {
    server.stubFor(
        post("/api/worker/claim")
            .willReturn(aResponse().withStatus(308).withHeader("Location", "https://x/")));

    assertThatThrownBy(() -> source.claim("pc-0", Set.of(Type.CHUNK)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void conflictIsALostLease() {
    server.stubFor(post("/api/worker/jobs/3/heartbeat").willReturn(aResponse().withStatus(409)));

    assertThatThrownBy(() -> source.heartbeat(JOB)).isInstanceOf(StaleLeaseException.class);
  }
}
