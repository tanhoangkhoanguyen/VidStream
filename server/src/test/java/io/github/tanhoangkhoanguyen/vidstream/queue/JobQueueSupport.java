package io.github.tanhoangkhoanguyen.vidstream.queue;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** One dataset with one video, and helpers to enqueue and inspect its jobs. */
@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
abstract class JobQueueSupport {

  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager txManager;
  JobQueue queue;
  long dataset;
  long video;

  @BeforeEach
  void setUp() {
    queue = new JobQueue(jdbc, txManager);
    dataset =
        jdbc.sql(
                "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                    + " fps, chunk_clips) VALUES ('d', '1', 12, 5, 30, 50) RETURNING id")
            .query(Long.class)
            .single();
    video =
        jdbc.sql(
                "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes)"
                    + " VALUES (?, '1', 'aa', 1) RETURNING id")
            .param(dataset)
            .query(Long.class)
            .single();
  }

  long enqueue(Type type, int chunk) {
    return jdbc.sql(
            "INSERT INTO jobs (type, dataset_id, video_id, payload)"
                + " VALUES (?, ?, ?, jsonb_build_object('chunk', ?)) RETURNING id")
        .params(type.name(), dataset, video, chunk)
        .query(Long.class)
        .single();
  }

  Optional<Job> claim(String worker) {
    return queue.claim(worker, List.of(Type.values()), List.of(dataset));
  }

  /** "status worker attempts". */
  String state(long id) {
    return jdbc.sql("SELECT concat_ws(' ', status, worker_id, attempts) FROM jobs WHERE id = ?")
        .param(id)
        .query(String.class)
        .single();
  }

  boolean check(long id, String condition) {
    return jdbc.sql("SELECT " + condition + " FROM jobs WHERE id = ?")
        .param(id)
        .query(Boolean.class)
        .single();
  }

  void update(String set) {
    jdbc.sql("UPDATE jobs SET " + set).update();
  }
}
