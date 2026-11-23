package io.github.tanhoangkhoanguyen.vidstream.media;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads a video's facts with ffprobe, straight from its URL: only the headers are fetched. */
@Component
public class Ffprobe {

  static final Duration TIMEOUT = Duration.ofMinutes(2);

  /**
   * @param hasVideo a real video stream; cover art (an attached picture) does not count
   */
  public record Facts(long durationMs, boolean hasVideo) {}

  private record Output(Format format, List<Stream> streams) {}

  private record Format(String duration) {}

  private record Stream(@JsonProperty("codec_type") String codecType, Disposition disposition) {}

  private record Disposition(@JsonProperty("attached_pic") int attachedPic) {}

  /** A written clip's video stream. */
  public record Clip(long frames, long startMs) {}

  private record ClipOutput(List<ClipStream> streams) {}

  private record ClipStream(
      @JsonProperty("nb_read_packets") long frames, @JsonProperty("start_time") String start) {}

  private static final JsonMapper JSON = new JsonMapper();

  /**
   * @throws IOException ffprobe failed (e.g. an expired URL), timed out or found no duration
   * @throws InterruptedException cancelled; ffprobe is killed
   */
  public Facts probe(URI url) throws IOException, InterruptedException {
    var json =
        Processes.run(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-print_format",
                "json",
                "-show_entries",
                "format=duration:stream=codec_type:stream_disposition=attached_pic",
                url.toString()),
            TIMEOUT);
    return facts(JSON.readValue(json, Output.class));
  }

  /** Frame count (packets: nothing is decoded) and start of a clip file's video. */
  public Clip clip(Path file) throws IOException, InterruptedException {
    var command =
        new ArrayList<>(
            List.of(
                "ffprobe -v error -select_streams v:0 -count_packets -print_format json"
                    .split(" ")));
    command.addAll(List.of("-show_entries", "stream=nb_read_packets,start_time", file.toString()));
    var streams = JSON.readValue(Processes.run(command, TIMEOUT), ClipOutput.class).streams();
    if (streams == null || streams.isEmpty()) throw new IOException("ffprobe: no video in clip");
    return new Clip(streams.getFirst().frames(), ms(streams.getFirst().start()));
  }

  private static Facts facts(Output output) throws IOException {
    var duration = output.format() == null ? null : output.format().duration();
    if (duration == null || duration.equals("N/A")) throw new IOException("ffprobe: no duration");
    long ms = ms(duration);
    var streams = output.streams() == null ? List.<Stream>of() : output.streams();
    boolean hasVideo =
        streams.stream()
            .anyMatch(s ->
                "video".equals(s.codecType())
                    && (s.disposition() == null || s.disposition().attachedPic() == 0));
    return new Facts(ms, hasVideo);
  }

  /** ffprobe seconds as ms, floored: a plan never reaches past the last frame. */
  private static long ms(String seconds) {
    return new BigDecimal(seconds).movePointRight(3).setScale(0, RoundingMode.FLOOR).longValue();
  }
}
