package io.github.tanhoangkhoanguyen.vidstream.media;

import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan.Clip;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Cuts a chunk's clips in one ffmpeg run, straight from the video's URL: only the chunk's part is
 * downloaded. Flags and why: docs/spikes/ffmpeg.md.
 */
@Component
public class Ffmpeg {

  static final Duration MIN_TIMEOUT = Duration.ofMinutes(5);

  /** Clip file names in the output dir; the number is the clip index. */
  public static final String CLIP_NAME = "%05d.mp4";

  private final Ffprobe ffprobe;

  public Ffmpeg(Ffprobe ffprobe) {
    this.ffprobe = ffprobe;
  }

  /**
   * Writes each clip as {@link #CLIP_NAME} in {@code dir}, then checks it: the planned frame count
   * (±1) from 0. Segments start on a keyframe by construction (-force_key_frames).
   *
   * @param clips consecutive clips of one chunk
   * @param dir empty
   * @throws IOException ffmpeg failed (e.g. an expired URL), ran past max(5 min, 3 × chunk), or a
   *     clip is not as planned
   * @throws InterruptedException cancelled; ffmpeg is killed
   */
  public void clipChunk(URI url, List<Clip> clips, int fps, Path dir)
      throws IOException, InterruptedException {
    long chunkMs = clips.getLast().endMs() - clips.getFirst().startMs();
    var timeout = Duration.ofMillis(3 * chunkMs);
    Processes.run(
        command(url, clips, fps, dir), timeout.compareTo(MIN_TIMEOUT) > 0 ? timeout : MIN_TIMEOUT);
    verify(clips, fps, dir);
  }

  private void verify(List<Clip> clips, int fps, Path dir)
      throws IOException, InterruptedException {
    try (var files = Files.list(dir)) {
      if (files.count() != clips.size())
        throw new IOException("ffmpeg wrote a different number of clips than planned");
    }
    for (var clip : clips) {
      var file = dir.resolve(CLIP_NAME.formatted(clip.index()));
      if (!Files.isRegularFile(file)) throw new IOException("clip " + clip.index() + " missing");
      var written = ffprobe.clip(file);
      long planned = Math.round((clip.endMs() - clip.startMs()) * fps / 1000.0);
      if (Math.abs(written.frames() - planned) > 1 || written.startMs() != 0)
        throw new IOException(
            "clip %d: %d frames from %d ms, planned %d from 0"
                .formatted(clip.index(), written.frames(), written.startMs(), planned));
    }
  }

  static List<String> command(URI url, List<Clip> clips, int fps, Path dir) {
    long start = clips.getFirst().startMs();
    long end = clips.getLast().endMs();
    // Boundaries inside the chunk, relative to its start: input seeking starts the output at 0.
    var boundaries =
        clips.stream()
            .skip(1)
            .map(c -> seconds(c.startMs() - start))
            .collect(Collectors.joining(","));
    var command = new ArrayList<>(flags("ffmpeg -v error -nostdin -y"));
    command.addAll(List.of("-ss", seconds(start), "-to", seconds(end), "-i", url.toString()));
    command.addAll(flags("-map 0:v:0 -map 0:a? -vf fps=" + fps));
    command.addAll(flags("-c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a aac"));
    command.addAll(flags("-avoid_negative_ts disabled"));
    // Without -t, the chunk's last clip gets 1–2 extra frames from 29.97 fps sources.
    command.addAll(List.of("-t", seconds(end - start)));
    if (boundaries.isEmpty()) {
      // One clip: a plain file. The segment muxer without -segment_times cuts every 2 s.
      command.addAll(flags("-movflags +faststart"));
      command.add(dir.resolve(CLIP_NAME.formatted(clips.getFirst().index())).toString());
      return command;
    }
    command.addAll(List.of("-force_key_frames", boundaries, "-f", "segment"));
    command.addAll(List.of("-segment_times", boundaries));
    command.addAll(List.of("-segment_time_delta", String.format(Locale.ROOT, "%.6f", 0.5 / fps)));
    command.addAll(List.of("-segment_start_number", String.valueOf(clips.getFirst().index())));
    command.addAll(flags("-reset_timestamps 1 -segment_format_options movflags=+faststart"));
    command.add(dir.resolve(CLIP_NAME).toString());
    return command;
  }

  /** Fixed flags, written space-separated; never user data. */
  private static List<String> flags(String flags) {
    return List.of(flags.split(" "));
  }

  /** Milliseconds as ffmpeg seconds, e.g. 12.345. */
  private static String seconds(long ms) {
    return String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000);
  }
}
