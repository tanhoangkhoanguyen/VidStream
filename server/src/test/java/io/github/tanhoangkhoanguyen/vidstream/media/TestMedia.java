package io.github.tanhoangkhoanguyen.vidstream.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Generated media served over HTTP like Box download URLs: with Range (206), so ffmpeg seeks to a
 * chunk and to a {@code moov} at the end. Needs ffmpeg; never real data.
 */
public final class TestMedia implements AutoCloseable {

  /** 40 s of a 30 fps test pattern with a tone. */
  public static final String VIDEO_40S =
      "-f lavfi -i testsrc=duration=40:size=160x120:rate=30 -f lavfi -i sine=duration=40"
          + " -c:v mpeg4 -c:a aac";

  /** 5 s of a tone, no video. */
  public static final String AUDIO_5S = "-f lavfi -i sine=duration=5 -c:a aac";

  private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");

  private final Path dir;
  private final HttpServer server;

  public TestMedia(Path dir) throws IOException {
    this.dir = dir.toAbsolutePath();
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::serve);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  private void serve(HttpExchange exchange) throws IOException {
    try (exchange) {
      var file = dir.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
      if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      long size = Files.size(file);
      long start = 0;
      long end = size - 1;
      var range =
          RANGE.matcher(Objects.toString(exchange.getRequestHeaders().getFirst("Range"), ""));
      boolean partial = range.matches();
      if (partial) {
        start = Long.parseLong(range.group(1));
        if (!range.group(2).isEmpty()) end = Math.min(end, Long.parseLong(range.group(2)));
        exchange
            .getResponseHeaders()
            .set("Content-Range", "bytes " + start + "-" + end + "/" + size);
      }
      exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
      // ffmpeg reuses a connection after hanging up mid-body to seek; this server then drops it.
      exchange.getResponseHeaders().set("Connection", "close");
      exchange.sendResponseHeaders(partial ? 206 : 200, end - start + 1);
      // Streamed: ranges of a large file, served at once, would not fit in the test heap.
      try (var in = FileChannel.open(file)) {
        var out = Channels.newChannel(exchange.getResponseBody());
        for (long at = start; at <= end; ) at += in.transferTo(at, end - at + 1, out);
      } catch (IOException e) {
        // ffmpeg hung up to seek elsewhere.
      }
    }
  }

  /** Writes {@code file} with ffmpeg; {@code args} are space-separated, none contains a space. */
  public TestMedia ffmpeg(String file, String args) throws IOException, InterruptedException {
    var command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y"));
    command.addAll(List.of(args.split(" ")));
    command.add(dir.resolve(file).toString());
    assertThat(new ProcessBuilder(command).inheritIO().start().waitFor()).isZero();
    return this;
  }

  public URI url(String file) {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + file);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
