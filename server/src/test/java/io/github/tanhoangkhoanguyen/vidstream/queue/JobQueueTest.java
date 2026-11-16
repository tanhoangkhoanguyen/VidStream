package io.github.tanhoangkhoanguyen.vidstream.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class JobQueueTest {

  @Test
  void sanitizeRemovesUrls() {
    var cause = new IOException("GET https://dl.boxcloud.com/d/1/x?token=secret failed: 403");
    assertThat(JobQueue.sanitize(cause)).isEqualTo("IOException: GET <url> failed: 403");
  }

  @Test
  void sanitizeKeepsClassWithoutMessageAndTruncates() {
    assertThat(JobQueue.sanitize(new IllegalStateException())).isEqualTo("IllegalStateException");
    assertThat(JobQueue.sanitize(new IOException("x".repeat(1000)))).hasSize(300);
  }
}
