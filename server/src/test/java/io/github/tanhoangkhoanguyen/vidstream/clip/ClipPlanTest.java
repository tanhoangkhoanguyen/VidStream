package io.github.tanhoangkhoanguyen.vidstream.clip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan.Clip;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClipPlanTest {

  @Test
  void splitsExactMultiple() {
    assertThat(ClipPlan.clips(36_000, 12, 5))
        .containsExactly(
            new Clip(0, 0, 12_000), new Clip(1, 12_000, 24_000), new Clip(2, 24_000, 36_000));
  }

  @Test
  void mergesShortRemainderIntoPreviousClip() {
    assertThat(ClipPlan.clips(40_000, 12, 5))
        .containsExactly(
            new Clip(0, 0, 12_000), new Clip(1, 12_000, 24_000), new Clip(2, 24_000, 40_000));
  }

  @Test
  void keepsRemainderOfMinimumLength() {
    assertThat(ClipPlan.clips(41_000, 12, 5)).last().isEqualTo(new Clip(3, 36_000, 41_000));
  }

  @Test
  void shortVideoIsOneClip() {
    assertThat(ClipPlan.clips(3_000, 12, 5)).containsExactly(new Clip(0, 0, 3_000));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void rejectsNonPositiveDuration(long durationMs) {
    assertThatIllegalArgumentException().isThrownBy(() -> ClipPlan.clips(durationMs, 12, 5));
  }

  @Test
  void clipsAreContiguousAndCoverTheVideo() {
    var random = new Random(42);
    for (int run = 0; run < 10_000; run++) {
      long duration = 1 + random.nextLong(4 * 3_600_000L);
      int clipSeconds = 5 + random.nextInt(56);
      int minLast = 1 + random.nextInt(clipSeconds);
      var clips = ClipPlan.clips(duration, clipSeconds, minLast);

      assertThat(clips.getFirst().startMs()).isZero();
      assertThat(clips.getLast().endMs()).isEqualTo(duration);
      for (int i = 0; i < clips.size(); i++) {
        var clip = clips.get(i);
        assertThat(clip.index()).isEqualTo(i);
        if (i > 0) assertThat(clip.startMs()).isEqualTo(clips.get(i - 1).endMs());
        // Never longer than a clip plus a merged remainder; only a lone clip may be shorter.
        assertThat(clip.endMs() - clip.startMs()).isLessThan((clipSeconds + minLast) * 1000L);
        if (clips.size() > 1)
          assertThat(clip.endMs() - clip.startMs()).isGreaterThanOrEqualTo(minLast * 1000L);
      }
    }
  }
}
