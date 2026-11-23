package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerTokensTest {

  final WorkerTokens tokens =
      new WorkerTokens(
          Map.of(
              "gcp", WorkerTokens.sha256("gcp-token"),
              "oracle", WorkerTokens.sha256("oracle-token").toUpperCase()));

  @Test
  void configuredTokenNamesItsWorker() {
    assertThat(tokens.authenticate("gcp-token")).contains("gcp");
    assertThat(tokens.authenticate("oracle-token")).contains("oracle");
  }

  @Test
  void unknownTokenIsRejected() {
    assertThat(tokens.authenticate("gcp-token ")).isEmpty();
    assertThat(tokens.authenticate(WorkerTokens.sha256("gcp-token"))).isEmpty(); // Not the hash.
  }

  @Test
  void badConfigFailsStartup() {
    var hash = WorkerTokens.sha256("t");
    assertThatThrownBy(() -> new WorkerTokens(Map.of("GCP:1", hash)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WorkerTokens(Map.of("gcp", "t")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WorkerTokens(Map.of("a", hash, "b", hash)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
