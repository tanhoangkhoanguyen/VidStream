package io.github.tanhoangkhoanguyen.vidstream.media;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Runs a program with an argument list (no shell); kills it on timeout or interrupt. */
final class Processes {

  private Processes() {}

  /**
   * @return its stdout
   * @throws IOException it exited non-zero (with the last lines of stderr) or timed out
   * @throws InterruptedException cancelled; the program is killed
   */
  static String run(List<String> command, Duration timeout)
      throws IOException, InterruptedException {
    var program = command.getFirst();
    var process = new ProcessBuilder(command).start();
    // Both pipes drain on their own threads, so neither can fill up and block the program.
    var out =
        CompletableFuture.supplyAsync(
            () -> read(process.getInputStream()), Thread::startVirtualThread);
    var err =
        CompletableFuture.supplyAsync(
            () -> tail(process.getErrorStream()), Thread::startVirtualThread);
    try {
      if (!process.waitFor(timeout.toMillis(), MILLISECONDS))
        throw new IOException(program + " timed out after " + timeout);
    } finally {
      // Already exited, or timed out or interrupted: never leaves an orphan behind.
      process.destroyForcibly().onExit().join();
    }
    if (process.exitValue() != 0)
      throw new IOException(program + " exited " + process.exitValue() + ": " + err.join());
    return out.join();
  }

  private static String read(InputStream in) {
    try (in) {
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The last lines of stderr; may name a URL, so callers sanitize before storing. */
  private static String tail(InputStream in) {
    var lines = new ArrayDeque<String>();
    try (var reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        if (lines.size() == 5) lines.removeFirst();
        lines.addLast(line);
      }
    } catch (IOException e) {
      // Killed mid-line: keep what was read.
    }
    return String.join(" | ", lines);
  }
}
