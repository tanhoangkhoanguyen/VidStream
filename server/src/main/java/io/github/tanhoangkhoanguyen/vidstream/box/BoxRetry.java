package io.github.tanhoangkhoanguyen.vidstream.box;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Retries 429 after {@code Retry-After} and 5xx with jittered backoff, at most 3 times. */
final class BoxRetry implements ClientHttpRequestInterceptor {

  static final int MAX_RETRIES = 2;
  private static final long BASE_MS = 500;
  private static final long MAX_WAIT_S = 60;
  private static final Logger log = LoggerFactory.getLogger(BoxRetry.class);

  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final Sleeper sleeper;

  BoxRetry(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    for (int retry = 0; ; retry++) {
      var response = execution.execute(request, body);
      int status = response.getStatusCode().value();
      if (retry == MAX_RETRIES || (status != 429 && status < 500)) return response;

      var wait = status == 429 ? retryAfter(response, retry) : backoff(retry);
      response.close();
      // No URL: download URLs are credentials.
      log.warn(
          "Box {} {}: retry {}/{} in {} ms",
          request.getMethod(),
          status,
          retry + 1,
          MAX_RETRIES,
          wait.toMillis());
      try {
        sleeper.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted while waiting to retry Box");
      }
    }
  }

  private static Duration retryAfter(ClientHttpResponse response, int retry) {
    var header = response.getHeaders().getFirst("Retry-After");
    try {
      return Duration.ofSeconds(Math.min(Long.parseLong(header), MAX_WAIT_S));
    } catch (NumberFormatException e) {
      return backoff(retry);
    }
  }

  /** Between half and all of 500 ms × 2^retry. */
  static Duration backoff(int retry) {
    long max = BASE_MS << retry;
    return Duration.ofMillis(max / 2 + ThreadLocalRandom.current().nextLong(max / 2 + 1));
  }
}
