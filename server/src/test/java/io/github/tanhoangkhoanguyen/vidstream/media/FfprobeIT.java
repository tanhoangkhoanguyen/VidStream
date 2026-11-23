package io.github.tanhoangkhoanguyen.vidstream.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfprobeIT {

  @TempDir static Path dir;
  static TestMedia media;
  final Ffprobe ffprobe = new Ffprobe();

  @BeforeAll
  static void serveMedia() throws Exception {
    media =
        new TestMedia(dir)
            .ffmpeg("video.mp4", TestMedia.VIDEO_40S)
            .ffmpeg("audio.m4a", TestMedia.AUDIO_5S);
  }

  @AfterAll
  static void stop() {
    media.close();
  }

  @Test
  void readsDurationOverHttp() throws Exception {
    var facts = ffprobe.probe(media.url("video.mp4"));

    assertThat(facts.hasVideo()).isTrue();
    assertThat(facts.durationMs()).isBetween(39_950L, 40_100L);
  }

  @Test
  void audioHasNoVideo() throws Exception {
    assertThat(ffprobe.probe(media.url("audio.m4a")).hasVideo()).isFalse();
  }

  @Test
  void missingFileFails() {
    assertThatThrownBy(() -> ffprobe.probe(media.url("gone.mp4")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("ffprobe exited");
  }
}
