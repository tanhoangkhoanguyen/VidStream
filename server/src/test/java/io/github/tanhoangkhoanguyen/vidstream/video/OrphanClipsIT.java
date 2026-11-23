package io.github.tanhoangkhoanguyen.vidstream.video;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore;
import io.github.tanhoangkhoanguyen.vidstream.storage.GarageContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dataset "d" with one committed clip and one orphan from a stale attempt. */
@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class OrphanClipsIT {

  static final GarageContainer GARAGE = new GarageContainer();
  static ClipStore store;

  @Autowired JdbcTemplate jdbc;

  @BeforeAll
  static void start() throws Exception {
    GARAGE.start();
    store = new ClipStore(GARAGE.bootstrap("clips-d"));
  }

  @AfterAll
  static void stop() {
    store.close();
    GARAGE.stop();
  }

  @BeforeEach
  void setUp() throws Exception {
    long dataset =
        jdbc.queryForObject(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                + " fps, chunk_clips) VALUES ('d', '1', 10, 5, 30, 2) RETURNING id",
            Long.class);
    long video =
        jdbc.queryForObject(
            "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes, status)"
                + " VALUES (?, '1', 'aa', 1, 'PROBED') RETURNING id",
            Long.class,
            dataset);
    committed = video + "/00000-7-2.mp4";
    jdbc.update(
        "INSERT INTO clips (video_id, clip_index, start_ms, end_ms, object_key, sha256,"
            + " size_bytes) VALUES (?, 0, 0, 10000, ?, 'ab', 4)",
        video,
        committed);
    GARAGE.write("clips-d", committed, new byte[4]);
    GARAGE.write("clips-d", video + "/00000-7-1.mp4", new byte[4]); // A stale attempt's upload.
    GARAGE.write("clips-d", "999999/00000-1-1.mp4", new byte[4]); // Its video is gone.
    GARAGE.write("clips-d", "notes/readme", new byte[4]); // Not a video's: left alone.
  }

  String committed;

  /** The bucket outlives a test; the rows are rolled back. */
  @AfterEach
  void emptyBucket() {
    objects().forEach(key -> store.delete("clips-d", key));
  }

  List<String> objects() {
    var keys = new ArrayList<String>();
    for (var prefix : store.prefixes("clips-d"))
      keys.addAll(store.keysOlderThan("clips-d", prefix, Instant.now().plusSeconds(1)));
    return keys;
  }

  @Test
  void deletesOnlyUnreferencedObjects() {
    new OrphanClips(jdbc, store, Duration.ZERO).sweep();

    assertThat(objects()).containsExactlyInAnyOrder(committed, "notes/readme");
  }

  @Test
  void keepsRecentUploads() {
    new OrphanClips(jdbc, store, OrphanClips.MIN_AGE).sweep();

    assertThat(objects()).hasSize(4);
  }
}
