package io.github.tanhoangkhoanguyen.vidstream.video;

import static io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type.CHUNK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Every chunk of a video completing at once, each in its own transaction, as on the web node. */
@JdbcTest(properties = "vidstream.role=web")
@Import({PostgresTestConfig.class, JobQueue.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED) // Commits race: nothing is rolled back.
class ChunkCommitIT {

  static final int CLIPS = 4;
  static final int ROUNDS = 20;

  @Autowired JdbcTemplate jdbc;
  @Autowired JobQueue queue;
  long dataset;

  @BeforeEach
  void setUp() {
    dataset =
        jdbc.queryForObject(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                + " fps, chunk_clips) VALUES ('race', 'race', 10, 5, 30, 1) RETURNING id",
            Long.class);
  }

  @AfterEach
  void cleanUp() {
    jdbc.update(
        "DELETE FROM units WHERE video_id IN (SELECT id FROM videos WHERE dataset_id = ?)",
        dataset);
    jdbc.update(
        "DELETE FROM clips WHERE video_id IN (SELECT id FROM videos WHERE dataset_id = ?)",
        dataset);
    jdbc.update("DELETE FROM jobs WHERE dataset_id = ?", dataset);
    jdbc.update("DELETE FROM videos WHERE dataset_id = ?", dataset);
    jdbc.update("DELETE FROM datasets WHERE id = ?", dataset);
  }

  @Test
  void exactlyOneOfChunksCommittingAtOnceClipsTheVideo() throws Exception {
    // Commit touches no object: the store is never called.
    var chunk = new ChunkJob(jdbc, (d, file) -> null, mock(ClipStore.class));
    for (int round = 0; round < ROUNDS; round++) {
      long video = probedVideo("v" + round);
      var jobs = new ArrayList<Job>();
      for (int i = 0; i < CLIPS; i++)
        jobs.add(queue.claim("w:" + i, List.of(CHUNK), List.of(dataset)).orElseThrow());

      var start = new CountDownLatch(1);
      var commits = new ArrayList<Future<?>>();
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var job : jobs)
          commits.add(
              pool.submit(() -> {
                start.await();
                queue.complete(job, () -> chunk.commit(job, List.of(blob(job))));
                return null;
              }));
        start.countDown();
        // A second chunk making the units fails here: active units never overlap.
        for (var commit : commits) commit.get();
      }

      assertThat(
              jdbc.queryForObject(
                  "SELECT status || ' ' || (SELECT count(*) FROM units WHERE video_id = ?)"
                      + " FROM videos WHERE id = ?",
                  String.class,
                  video,
                  video))
          .as("round %d", round)
          .isEqualTo("CLIPPED " + CLIPS);
    }
  }

  /** CLIPS clips of 10 s, one CHUNK job each. */
  long probedVideo(String file) {
    long video =
        jdbc.queryForObject(
            "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes, duration_ms, status)"
                + " VALUES (?, ?, ?, 1, ?, 'PROBED') RETURNING id",
            Long.class,
            dataset,
            file,
            file,
            CLIPS * 10_000);
    jdbc.update(
        "INSERT INTO clips (video_id, clip_index, start_ms, end_ms)"
            + " SELECT ?, i, i * 10000, (i + 1) * 10000 FROM generate_series(0, ? - 1) i",
        video,
        CLIPS);
    jdbc.update(
        "INSERT INTO jobs (type, dataset_id, video_id, payload) SELECT 'CHUNK', ?, ?,"
            + " jsonb_build_object('chunk', i, 'first', i, 'last', i)"
            + " FROM generate_series(0, ? - 1) i",
        dataset,
        video,
        CLIPS);
    return video;
  }

  /** The chunk's one clip, as its worker reports it. */
  static Blob blob(Job job) {
    return new Blob(new JsonMapper().readTree(job.payload()).get("first").asInt(), 4, "ab");
  }
}
