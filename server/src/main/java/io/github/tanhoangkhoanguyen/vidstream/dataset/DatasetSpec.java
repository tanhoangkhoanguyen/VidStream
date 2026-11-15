package io.github.tanhoangkhoanguyen.vidstream.dataset;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * One dataset in {@code datasets.yml}. Same rules as the database, checked first for clear errors.
 */
public record DatasetSpec(
    String name,
    String boxFolderId,
    int clipSeconds,
    int minLastClipSeconds,
    int fps,
    int chunkClips) {

  // A DNS label: Label Studio runs at <name>.<domain>.
  private static final Pattern NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
  // Box IDs are numeric; folder 0 is the user's root, never a dataset.
  private static final Pattern BOX_ID = Pattern.compile("[1-9][0-9]*");
  private static final Set<Integer> FPS = Set.of(24, 25, 30);

  public DatasetSpec {
    check(
        name != null && NAME.matcher(name).matches(),
        name,
        "name must be a DNS label (a-z, 0-9, -)");
    check(
        boxFolderId != null && BOX_ID.matcher(boxFolderId).matches(),
        name,
        "box_folder_id must be a Box folder ID, got " + boxFolderId);
    check(
        clipSeconds >= 5 && clipSeconds <= 60,
        name,
        "clip_seconds must be 5-60, got " + clipSeconds);
    check(
        minLastClipSeconds >= 1 && minLastClipSeconds <= clipSeconds,
        name,
        "min_last_clip_seconds must be 1-clip_seconds, got " + minLastClipSeconds);
    check(FPS.contains(fps), name, "fps must be 24, 25 or 30, got " + fps);
    check(chunkClips > 0, name, "chunk_clips must be positive, got " + chunkClips);
  }

  private static void check(boolean ok, String name, String problem) {
    if (!ok) throw new IllegalArgumentException("dataset '" + name + "': " + problem);
  }
}
