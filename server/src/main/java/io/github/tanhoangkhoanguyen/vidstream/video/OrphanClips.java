package io.github.tanhoangkhoanguyen.vidstream.video;

import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Deletes clip objects no row references: uploads of stale or crashed attempts. Run hourly;
 * deleting an orphan twice is harmless.
 */
@Component
@Profile("web")
public class OrphanClips {

  private static final Logger log = LoggerFactory.getLogger(OrphanClips.class);

  /** Far longer than an attempt takes from its first upload to its commit. */
  static final Duration MIN_AGE = Duration.ofHours(1);

  private static final Pattern VIDEO = Pattern.compile("[0-9]{1,18}/");

  private final JdbcTemplate jdbc;
  private final ClipStore store;
  private final Duration minAge;

  @Autowired
  OrphanClips(JdbcTemplate jdbc, ClipStore store) {
    this(jdbc, store, MIN_AGE);
  }

  OrphanClips(JdbcTemplate jdbc, ClipStore store, Duration minAge) {
    this.jdbc = jdbc;
    this.store = store;
    this.minAge = minAge;
  }

  /** Video by video: memory holds one video's keys, never a dataset's. */
  public void sweep() {
    var cutoff = Instant.now().minus(minAge);
    int deleted = 0;
    for (var dataset : jdbc.queryForList("SELECT name FROM datasets", String.class)) {
      var bucket = ClipStore.bucket(dataset);
      try {
        for (var prefix : store.prefixes(bucket)) deleted += sweep(bucket, prefix, cutoff);
      } catch (NoSuchBucketException e) {
        // No clips yet.
      }
    }
    log.info("Orphan clips: {} deleted", deleted);
  }

  /** One video's objects, under {@code <video id>/} (ChunkJob's keys); any other prefix stays. */
  private int sweep(String bucket, String prefix, Instant cutoff) {
    if (!VIDEO.matcher(prefix).matches()) return 0;
    // Listed before the references are read: a key committed in between counts as referenced.
    var old = store.keysOlderThan(bucket, prefix, cutoff);
    if (old.isEmpty()) return 0;
    long video = Long.parseLong(prefix.substring(0, prefix.length() - 1));
    var referenced =
        new HashSet<>(
            jdbc.queryForList(
                "SELECT object_key FROM clips WHERE video_id = ? AND object_key IS NOT NULL",
                String.class,
                video));
    int deleted = 0;
    for (var key : old) {
      if (referenced.contains(key)) continue;
      store.delete(bucket, key);
      deleted++;
    }
    return deleted;
  }
}
