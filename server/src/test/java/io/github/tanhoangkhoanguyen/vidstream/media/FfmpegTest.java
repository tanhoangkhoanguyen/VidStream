package io.github.tanhoangkhoanguyen.vidstream.media;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan.Clip;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FfmpegTest {

  static final URI URL = URI.create("https://dl.box.test/x");

  @Test
  void chunkBoundariesAreRelativeToItsStart() {
    var clips =
        List.of(
            new Clip(5, 60_000, 72_000), new Clip(6, 72_000, 84_000), new Clip(7, 84_000, 90_500));

    var command = String.join(" ", Ffmpeg.command(URL, clips, 30, Path.of("out")));

    assertThat(command)
        .contains("-ss 60.000 -to 90.500 -i https://dl.box.test/x")
        .contains("-vf fps=30")
        .contains("-t 30.500 -force_key_frames 12.000,24.000 -f segment")
        .contains("-segment_times 12.000,24.000")
        .contains("-segment_time_delta 0.016667")
        .contains("-segment_start_number 5")
        .endsWith(Path.of("out", "%05d.mp4").toString());
  }

  @Test
  void singleClipChunkHasNoBoundaries() {
    var command = Ffmpeg.command(URL, List.of(new Clip(0, 0, 4_000)), 25, Path.of("out"));

    // A plain file: the segment muxer without boundaries would cut every 2 s.
    assertThat(command).doesNotContain("segment", "-force_key_frames");
    assertThat(command.getLast()).isEqualTo(Path.of("out", "00000.mp4").toString());
  }
}
