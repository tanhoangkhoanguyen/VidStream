package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.queue.StaleLeaseException;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Assignment;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import io.github.tanhoangkhoanguyen.vidstream.worker.WireJson;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * HTTP mapping only; JobService and the queue have their own tests. Token "t" is worker "lab"'s.
 */
@WebMvcTest(value = WorkerApi.class, properties = "vidstream.role=web")
@Import(WorkerApiSecurity.class)
class WorkerApiTest {

  static final Job JOB = new Job(3, Type.PROBE, 1, 2L, "{}", 5);

  @Autowired MockMvcTester mvc;
  @MockitoBean JobService jobs;
  @MockitoBean WorkerTokens tokens;

  @BeforeEach
  void token() {
    given(tokens.authenticate("t")).willReturn(Optional.of("lab"));
  }

  MvcTestResult post(String path, String body) {
    return mvc.post()
        .uri("/api/worker" + path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer t")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  @Test
  void claimGivesTheJobAndItsWorkToThisTokensWorker() {
    var work = WireJson.MAPPER.valueToTree("https://dl.box.test/1");
    given(jobs.claim("lab:pc-0", Set.of(Type.PROBE)))
        .willReturn(Optional.of(new Assignment(JOB, work)));

    assertThat(post("/claim", "{\"slot\": \"pc-0\", \"types\": [\"PROBE\"]}"))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"job": {"id": 3, "type": "PROBE", "leaseVersion": 5},
             "work": "https://dl.box.test/1"}
            """);
  }

  @Test
  void nothingToClaimIsNoContent() {
    given(jobs.claim(anyString(), any())).willReturn(Optional.empty());

    assertThat(post("/claim", "{\"slot\": \"pc-0\", \"types\": [\"PROBE\"]}"))
        .hasStatus(HttpStatus.NO_CONTENT);
  }

  @Test
  void reportsAreOnTheJobAsStoredForThisToken() {
    given(jobs.leased(3, 5, "lab:")).willReturn(JOB);

    assertThat(post("/jobs/3/heartbeat", "{\"leaseVersion\": 5}")).hasStatus(HttpStatus.NO_CONTENT);
    assertThat(post("/jobs/3/complete", "{\"leaseVersion\": 5, \"result\": 1}"))
        .hasStatus(HttpStatus.NO_CONTENT);
    assertThat(post("/jobs/3/fail", "{\"leaseVersion\": 5, \"error\": \"x\"}"))
        .hasStatus(HttpStatus.NO_CONTENT);

    verify(jobs).heartbeat(JOB);
    verify(jobs).complete(JOB, WireJson.MAPPER.valueToTree(1));
    verify(jobs).fail(JOB, "x");
  }

  @Test
  void uploadsAreSignedForTheJobsOwnFiles() {
    given(jobs.leased(3, 5, "lab:")).willReturn(JOB);
    var blob = new Blob(2, 3, "ab");
    given(jobs.uploads(JOB, List.of(blob)))
        .willReturn(List.of(new Upload(URI.create("https://s3.test/k"), Map.of("h", "v"))));
    given(jobs.uploads(JOB, List.of(new Blob(9, 3, "ab"))))
        .willThrow(new IllegalArgumentException("clip 9 is not in the chunk"));

    assertThat(post("/jobs/3/uploads", uploads(2)))
        .hasStatusOk()
        .bodyJson()
        .isStrictlyEqualTo("[{\"url\": \"https://s3.test/k\", \"headers\": {\"h\": \"v\"}}]");
    assertThat(post("/jobs/3/uploads", uploads(9))).hasStatus(HttpStatus.BAD_REQUEST);
  }

  static String uploads(int index) {
    return "{\"leaseVersion\": 5, \"blobs\":"
        + " [{\"index\": %d, \"size\": 3, \"sha256\": \"ab\"}]}".formatted(index);
  }

  @Test
  void lostOrForeignLeaseIsConflict() {
    given(jobs.leased(3, 5, "lab:")).willThrow(new StaleLeaseException(3, 5));

    assertThat(post("/jobs/3/heartbeat", "{\"leaseVersion\": 5}")).hasStatus(HttpStatus.CONFLICT);
  }

  @Test
  void badRequestsAreRejected() {
    assertThat(post("/claim", "{\"slot\": \"a b\", \"types\": [\"PROBE\"]}"))
        .hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(post("/claim", "{\"slot\": \"pc-0\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(post("/jobs/3/complete", "{\"leaseVersion\": 5}")).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(post("/jobs/3/release", "{\"leaseVersion\": 5, \"delayMs\": -1}"))
        .hasStatus(HttpStatus.BAD_REQUEST);
  }
}
