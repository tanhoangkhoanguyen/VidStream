package io.github.tanhoangkhoanguyen.vidstream.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A 40 s video (moov at the end) in 10 s clips: two chunks of two clips vs one pass. */
class FfmpegIT {

  @TempDir static Path dir;
  static TestMedia media;
  static final List<ClipPlan.Clip> CLIPS = ClipPlan.clips(40_000, 10, 5);
  final Ffmpeg ffmpeg = new Ffmpeg(new Ffprobe());

  @BeforeAll
  static void serveMedia() throws Exception {
    media = new TestMedia(dir).ffmpeg("video.mp4", TestMedia.VIDEO_40S);
  }

  @AfterAll
  static void stop() {
    media.close();
  }

  Path clip(String run, List<ClipPlan.Clip> clips) throws Exception {
    var out = Files.createDirectories(dir.resolve(run));
    ffmpeg.clipChunk(media.url("video.mp4"), clips, 30, out);
    return out;
  }

  static long frames(Path clip) throws Exception {
    return new Ffprobe().clip(clip).frames();
  }

  /** PSNR of the first frames of two clips, in dB; identical frames give infinity. */
  static double firstFramePsnr(Path a, Path b) throws Exception {
    var stats =
        Processes.run(
            List.of(
                "ffmpeg",
                "-v",
                "error",
                "-i",
                a.toString(),
                "-i",
                b.toString(),
                "-lavfi",
                "[0:v][1:v]psnr=stats_file=-",
                "-frames:v",
                "1",
                "-f",
                "null",
                "-"),
            Duration.ofSeconds(30));
    var psnr = Pattern.compile("psnr_avg:(\\S+)").matcher(stats);
    assertThat(psnr.find()).isTrue();
    return psnr.group(1).equals("inf")
        ? Double.POSITIVE_INFINITY
        : Double.parseDouble(psnr.group(1));
  }

  @Test
  void chunksMatchASinglePass() throws Exception {
    var single = clip("single", CLIPS);
    var first = clip("chunk0", CLIPS.subList(0, 2));
    var second = clip("chunk1", CLIPS.subList(2, 4));

    for (var name : List.of("00000.mp4", "00001.mp4", "00002.mp4", "00003.mp4")) {
      var chunked = (name.compareTo("00002.mp4") < 0 ? first : second).resolve(name);
      assertThat(frames(chunked)).as(name).isEqualTo(300).isEqualTo(frames(single.resolve(name)));
    }
    // The second chunk starts on the same frame as the single pass: same picture, not a neighbour.
    assertThat(firstFramePsnr(second.resolve("00002.mp4"), single.resolve("00002.mp4")))
        .isGreaterThan(35);
  }

  @Test
  void clipPastTheEndFails() {
    // Planned for a longer video: ffmpeg exits 0 with an empty clip; only the check catches it.
    var past = List.of(new ClipPlan.Clip(4, 40_000, 50_000));

    assertThatThrownBy(() -> clip("past", past))
        .isInstanceOf(IOException.class)
        .hasMessage("ffprobe: no video in clip");
  }
}
