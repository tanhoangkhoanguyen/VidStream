package io.github.tanhoangkhoanguyen.vidstream.worker;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.queue.StaleLeaseException;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.ResponseSpec;
import tools.jackson.databind.JsonNode;

/** The server's worker API, with a worker token: how every worker reaches the queue (#153). */
@Component
@Profile("worker")
public class HttpJobSource implements JobSource {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** Past the claim's long poll ({@code vidstream.dispatch.claim-wait}, 20 s by default). */
  static final Duration READ_TIMEOUT = Duration.ofMinutes(1);

  private static final ParameterizedTypeReference<List<Upload>> UPLOADS =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;

  @Autowired
  HttpJobSource(WorkerProperties props) {
    this(
        Objects.requireNonNull(props.server(), "vidstream.worker.server is not set"),
        Objects.requireNonNull(props.token(), "vidstream.worker.token is not set"));
  }

  /**
   * @param server e.g. {@code https://<domain>}
   */
  public HttpJobSource(URI server, String token) {
    // No redirects: the token is never sent elsewhere.
    var client =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(READ_TIMEOUT);
    this.http =
        RestClient.builder()
            .baseUrl(server.resolve("/api/worker").toString())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .requestFactory(factory)
            // Not followed: e.g. http:// sent to https://, or a sign-in page. Fail loudly instead
            // of
            // reading it as "no job".
            .defaultStatusHandler(
                HttpStatusCode::is3xxRedirection,
                (request, response) -> {
                  throw new IllegalStateException(
                      "worker API redirected (HTTP " + response.getStatusCode().value() + ")");
                })
            .build();
  }

  /** {@code worker} is the slot name; the server prefixes the token. */
  @Override
  public Optional<Assignment> claim(String worker, Set<Type> types) {
    var response =
        http.post()
            .uri("/claim")
            .body(Map.of("slot", worker, "types", types))
            .retrieve()
            .toEntity(Assignment.class);
    return Optional.ofNullable(response.getBody()); // 204: none within the long poll.
  }

  @Override
  public void heartbeat(Job job) {
    report(job, "heartbeat", Map.of(), ResponseSpec::toBodilessEntity);
  }

  @Override
  public List<Upload> uploads(Job job, List<Blob> blobs) {
    return report(job, "uploads", Map.of("blobs", blobs), r -> r.body(UPLOADS));
  }

  @Override
  public void complete(Job job, JsonNode result) {
    report(job, "complete", Map.of("result", result), ResponseSpec::toBodilessEntity);
  }

  @Override
  public void fail(Job job, String error) {
    report(job, "fail", Map.of("error", error), ResponseSpec::toBodilessEntity);
  }

  @Override
  public void release(Job job, Duration delay) {
    report(job, "release", Map.of("delayMs", delay.toMillis()), ResponseSpec::toBodilessEntity);
  }

  /** A report on the job's lease; 409 means it is lost. */
  private <T> T report(
      Job job, String action, Map<String, Object> fields, Function<ResponseSpec, T> read) {
    var body = new HashMap<>(fields);
    body.put("leaseVersion", job.leaseVersion());
    try {
      return read.apply(
          http.post().uri("/jobs/{id}/{action}", job.id(), action).body(body).retrieve());
    } catch (HttpClientErrorException.Conflict e) {
      throw new StaleLeaseException(job.id(), job.leaseVersion());
    }
  }
}
