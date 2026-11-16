package io.github.tanhoangkhoanguyen.vidstream.clip;

import java.util.ArrayList;
import java.util.List;

/** Deterministic clip boundaries: the same video and dataset config always give the same clips. */
public final class ClipPlan {

  /** Clip {@code index} covers [startMs, endMs). */
  public record Clip(int index, long startMs, long endMs) {}

  private ClipPlan() {}

  /**
   * Boundaries at k × clip. A final remainder shorter than {@code minLastClipSeconds} merges into
   * the previous clip; a video shorter than one clip is one clip.
   */
  public static List<Clip> clips(long durationMs, int clipSeconds, int minLastClipSeconds) {
    if (durationMs <= 0) throw new IllegalArgumentException("duration must be positive");
    long clipMs = clipSeconds * 1000L;
    long full = durationMs / clipMs;
    long count = durationMs % clipMs >= minLastClipSeconds * 1000L ? full + 1 : full;
    int n = (int) Math.max(1, count);
    var clips = new ArrayList<Clip>(n);
    for (int i = 0; i < n; i++) {
      clips.add(new Clip(i, i * clipMs, i == n - 1 ? durationMs : (i + 1) * clipMs));
    }
    return clips;
  }
}
