package io.github.tanhoangkhoanguyen.vidstream.queue;

import static io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type.CHUNK;
import static io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type.PROBE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

// Each test runs in one rolled-back transaction, so now() is fixed within a test.
@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class JobQueueIT {

  @Autowired JdbcClient jdbc;
  JobQueue queue;
  long dataset;
  long video;

  @BeforeEach
  void setUp() {
    queue = new JobQueue(jdbc);
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

  @Test
  void claimsOldestJobWithFirstLease() {
    var first = enqueue(CHUNK, 0);
    enqueue(CHUNK, 1);

    assertThat(claim("w1")).hasValue(new Job(first, CHUNK, dataset, video, "{\"chunk\": 0}", 1));
    assertThat(state(first)).isEqualTo("LEASED w1 1");
  }

  @Test
  void neverClaimsLiveLease() {
    enqueue(CHUNK, 0);
    claim("w1");

    assertThat(claim("w2")).isEmpty();
  }

  @Test
  void reclaimsExpiredLeaseWithNewerVersion() {
    var id = enqueue(CHUNK, 0);
    claim("w1");
    update("lease_until = now() - interval '1 second'");

    assertThat(claim("w2")).get().extracting(Job::leaseVersion).isEqualTo(2);
    assertThat(state(id)).isEqualTo("LEASED w2 2");
  }

  @Test
  void skipsJobsNotDue() {
    enqueue(CHUNK, 0);
    update("run_after = now() + interval '1 minute'");

    assertThat(claim("w1")).isEmpty();
  }

  @Test
  void failsJobWhoseLastLeaseExpired() {
    var crashed = enqueue(CHUNK, 0);
    var next = enqueue(CHUNK, 1);
    update(
        "status = 'LEASED', worker_id = 'w0', attempts = "
            + JobQueue.MAX_ATTEMPTS
            + ", lease_until = now() - interval '1 second' WHERE id = "
            + crashed);

    assertThat(claim("w1")).get().extracting(Job::id).isEqualTo(next);
    assertThat(state(crashed)).isEqualTo("FAILED w0 " + JobQueue.MAX_ATTEMPTS);
  }

  @Test
  void claimsOnlyGivenTypesAndDatasets() {
    enqueue(PROBE, 0);

    assertThat(queue.claim("w1", List.of(CHUNK), List.of(dataset))).isEmpty();
    assertThat(queue.claim("w1", List.of(PROBE), List.of(dataset + 1))).isEmpty();
    assertThat(queue.claim("w1", List.of(PROBE), List.of())).isEmpty();
  }

  @Test
  void heartbeatExtendsLease() {
    var id = enqueue(CHUNK, 0);
    var job = claim("w1").orElseThrow();
    update("lease_until = now() + interval '1 second'");

    queue.heartbeat(job);

    assertThat(check(id, "lease_until = now() + interval '2 minutes'")).isTrue();
  }

  @Test
  void failRetriesLaterThenFailsOnLastAttempt() {
    var id = enqueue(CHUNK, 0);
    for (int attempt = 1; attempt < JobQueue.MAX_ATTEMPTS; attempt++) {
      queue.fail(claim("w1").orElseThrow(), new IOException("Box 503"));
      assertThat(state(id)).isEqualTo("PENDING w1 " + attempt);
      assertThat(check(id, "run_after = now() + interval '1 minute'")).isTrue();
      update("run_after = now()");
    }

    queue.fail(claim("w1").orElseThrow(), new IOException("Box 503"));

    assertThat(state(id)).isEqualTo("FAILED w1 " + JobQueue.MAX_ATTEMPTS);
    assertThat(check(id, "last_error = 'IOException: Box 503'")).isTrue();
  }

  @Test
  void releaseDoesNotCountTheAttempt() {
    var id = enqueue(CHUNK, 0);
    queue.release(claim("w1").orElseThrow(), Duration.ZERO);

    assertThat(state(id)).isEqualTo("PENDING w1 0");
    assertThat(claim("w2")).isPresent();
  }

  @Test
  void staleWorkerChangesNothing() {
    var id = enqueue(CHUNK, 0);
    var stale = claim("w1").orElseThrow();
    update("lease_until = now() - interval '1 second'");
    claim("w2");

    assertThatThrownBy(() -> queue.heartbeat(stale)).isInstanceOf(StaleLeaseException.class);
    assertThatThrownBy(() -> queue.fail(stale, new IOException()))
        .isInstanceOf(StaleLeaseException.class);
    assertThatThrownBy(() -> queue.release(stale, Duration.ZERO))
        .isInstanceOf(StaleLeaseException.class);
    assertThat(state(id)).isEqualTo("LEASED w2 2");
    assertThat(check(id, "lease_until = now() + interval '2 minutes'")).isTrue();
  }
}
