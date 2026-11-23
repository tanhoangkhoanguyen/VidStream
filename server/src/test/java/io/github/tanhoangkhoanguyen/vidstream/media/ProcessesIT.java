package io.github.tanhoangkhoanguyen.vidstream.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Needs ffmpeg on the PATH: an endless test pattern stands in for a long clipping run. */
class ProcessesIT {

  static final List<String> ENDLESS =
      List.of("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc", "-f", "null", "-");

  /** Live ffmpeg processes started by this JVM. */
  static long children() {
    return ProcessHandle.current()
        .children()
        .filter(p -> p.isAlive() && p.info().command().orElse("").contains("ffmpeg"))
        .count();
  }

  @Test
  void returnsStdout() throws Exception {
    assertThat(Processes.run(List.of("ffmpeg", "-version"), Duration.ofSeconds(30)))
        .startsWith("ffmpeg version");
  }

  @Test
  void failureCarriesStderr() {
    assertThatThrownBy(() ->
            Processes.run(
                List.of("ffmpeg", "-v", "error", "-i", "missing.mp4"), Duration.ofSeconds(30)))
        .isInstanceOf(IOException.class)
        .hasMessageStartingWith("ffmpeg exited ")
        .hasMessageContaining("missing.mp4");
  }

  @Test
  void timeoutKillsTheProgram() {
    assertThatThrownBy(() -> Processes.run(ENDLESS, Duration.ofMillis(500)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("timed out");
    assertThat(children()).isZero();
  }

  @Test
  void interruptKillsTheProgram() {
    var result = new CompletableFuture<Throwable>();
    var thread =
        Thread.ofVirtual()
            .start(() -> {
              try {
                Processes.run(ENDLESS, Duration.ofMinutes(1));
              } catch (Exception e) {
                result.complete(e);
              }
            });
    await().until(() -> children() == 1);

    thread.interrupt();

    assertThat(result.join()).isInstanceOf(InterruptedException.class);
    assertThat(children()).isZero(); // No orphan ffmpeg.
  }
}
