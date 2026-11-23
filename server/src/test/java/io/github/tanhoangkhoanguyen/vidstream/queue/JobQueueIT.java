package io.github.tanhoangkhoanguyen.vidstream.queue;

import static io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type.CHUNK;
import static io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type.PROBE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

// Each test runs in one rolled-back transaction, so now() is fixed within a test.
class JobQueueIT extends JobQueueSupport {

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
    // Not of the video: its pending jobs go with it.
    long next =
        jdbc.sql("INSERT INTO jobs (type, dataset_id) VALUES ('RETENTION', ?) RETURNING id")
            .param(dataset)
            .query(Long.class)
            .single();
    update(
        "status = 'LEASED', worker_id = 'w0', attempts = "
            + JobQueue.MAX_ATTEMPTS
            + ", lease_until = now() - interval '1 second' WHERE id = "
            + crashed);

    assertThat(claim("w1")).get().extracting(Job::id).isEqualTo(next);
    assertThat(state(crashed)).isEqualTo("FAILED w0 " + JobQueue.MAX_ATTEMPTS);
    assertThat(videoState()).isEqualTo("FAILED lease expired on the last attempt");
  }

  @Test
  void claimsOnlyGivenTypesAndDatasets() {
    enqueue(PROBE, 0);

    assertThat(queue.claim("w1", List.of(CHUNK), List.of(dataset))).isEmpty();
    assertThat(queue.claim("w1", List.of(PROBE), List.of(dataset + 1))).isEmpty();
    assertThat(queue.claim("w1", List.of(PROBE), List.of())).isEmpty();
  }

  @Test
  void datasetWithFewestLeasedJobsGoesFirst() {
    var a0 = enqueue(CHUNK, 0);
    var a1 = enqueue(CHUNK, 1);
    long other =
        jdbc.sql(
                "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                    + " fps, chunk_clips) VALUES ('e', '2', 12, 5, 30, 50) RETURNING id")
            .query(Long.class)
            .single();
    long b0 =
        jdbc.sql(
                """
                WITH v AS (INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes)
                           VALUES (?, '1', 'aa', 1) RETURNING id, dataset_id)
                INSERT INTO jobs (type, dataset_id, video_id, payload)
                SELECT 'CHUNK', dataset_id, id, '{"chunk": 0}' FROM v RETURNING id
                """)
            .param(other)
            .query(Long.class)
            .single();
    var both = List.of(dataset, other);

    // Ties go to the oldest job; then the dataset without a running job, though its job is newer.
    assertThat(queue.claim("w1", List.of(CHUNK), both)).get().extracting(Job::id).isEqualTo(a0);
    assertThat(queue.claim("w2", List.of(CHUNK), both)).get().extracting(Job::id).isEqualTo(b0);
    assertThat(queue.claim("w3", List.of(CHUNK), both)).get().extracting(Job::id).isEqualTo(a1);
  }

  @Test
  void leasedJobIsReadOnlyForItsHolderAndVersion() {
    enqueue(CHUNK, 0);
    var job = claim("t7:pc-0").orElseThrow();

    assertThat(queue.leased(job.id(), job.leaseVersion(), "t7:")).isEqualTo(job);
    assertThatThrownBy(() -> queue.leased(job.id(), job.leaseVersion(), "t70:"))
        .isInstanceOf(StaleLeaseException.class);
    assertThatThrownBy(() -> queue.leased(job.id(), job.leaseVersion() - 1, "t7:"))
        .isInstanceOf(StaleLeaseException.class);
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
      queue.fail(claim("w1").orElseThrow(), "IOException: Box 503");
      assertThat(state(id)).isEqualTo("PENDING w1 " + attempt);
      assertThat(check(id, "run_after = now() + interval '1 minute'")).isTrue();
      update("run_after = now()");
    }
    assertThat(videoState()).isEqualTo("DISCOVERED");

    queue.fail(claim("w1").orElseThrow(), "IOException: Box 503");

    assertThat(state(id)).isEqualTo("FAILED w1 " + JobQueue.MAX_ATTEMPTS);
    assertThat(check(id, "last_error = 'IOException: Box 503'")).isTrue();
    assertThat(videoState()).isEqualTo("FAILED IOException: Box 503");
  }

  @Test
  void failedVideoDropsItsPendingJobs() {
    var failing = enqueue(CHUNK, 0);
    var leased = enqueue(CHUNK, 1);
    enqueue(CHUNK, 2);
    update("attempts = " + (JobQueue.MAX_ATTEMPTS - 1) + " WHERE id = " + failing);
    var last = claim("w1").orElseThrow();
    claim("w2");

    queue.fail(last, "IOException: Box 404");

    assertThat(videoState()).isEqualTo("FAILED IOException: Box 404");
    // A running job is left to finish; its commit ignores the failed video.
    assertThat(jdbc.sql("SELECT id FROM jobs ORDER BY id").query(Long.class).list())
        .containsExactly(failing, leased);
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
    assertThatThrownBy(() -> queue.fail(stale, "IOException"))
        .isInstanceOf(StaleLeaseException.class);
    assertThatThrownBy(() -> queue.release(stale, Duration.ZERO))
        .isInstanceOf(StaleLeaseException.class);
    assertThat(state(id)).isEqualTo("LEASED w2 2");
    assertThat(check(id, "lease_until = now() + interval '2 minutes'")).isTrue();
  }
}
