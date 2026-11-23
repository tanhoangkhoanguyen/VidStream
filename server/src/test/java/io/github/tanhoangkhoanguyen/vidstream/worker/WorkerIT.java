package io.github.tanhoangkhoanguyen.vidstream.worker;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.auth.NoUsableMemberException;
import io.github.tanhoangkhoanguyen.vidstream.dispatch.JobNotifications;
import io.github.tanhoangkhoanguyen.vidstream.dispatch.JobService;
import io.github.tanhoangkhoanguyen.vidstream.dispatch.JobType;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

// Workers commit on their own threads: nothing is rolled back, so each test cleans up.
@JdbcTest(properties = "vidstream.role=web")
@Import({PostgresTestConfig.class, JobQueue.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkerIT {

  @Autowired JdbcClient jdbc;
  @Autowired JobQueue queue;
  @Autowired JdbcConnectionDetails db;
  Worker worker;
  // Not started unless a test listens: claims then wait out the poll.
  JobNotifications notifications;

  static final Duration BEAT = Duration.ofMillis(200);

  // Bytes all datasets may store together.
  long storeCapacity = Long.MAX_VALUE;

  /** Jobs whose results were committed. */
  final Set<Long> committed = ConcurrentHashMap.newKeySet();

  interface Slow {
    void run(Job job) throws Exception;
  }

  /** Both sides of a job type: executes {@code slow}; the commit records the job. */
  class Fake implements JobType<Long, Long>, JobExecutor<Long, Long> {

    final Type type;
    final boolean usesDisk;
    final Slow slow;

    Fake(Type type, boolean usesDisk, Slow slow) {
      this.type = type;
      this.usesDisk = usesDisk;
      this.slow = slow;
    }

    @Override
    public Type type() {
      return type;
    }

    @Override
    public boolean usesDisk() {
      return usesDisk;
    }

    @Override
    public Long prepare(Job job) {
      return job.id();
    }

    @Override
    public Long execute(Job job, Long id) throws Exception {
      slow.run(job);
      return id;
    }

    @Override
    public void commit(Job job, Long id) {
      committed.add(id);
    }
  }

  @AfterEach
  void cleanUp() {
    if (worker != null) worker.stop();
    if (notifications != null && notifications.isRunning()) notifications.stop();
    jdbc.sql("DELETE FROM jobs").update();
    jdbc.sql("DELETE FROM clips").update();
    jdbc.sql("DELETE FROM videos").update();
    jdbc.sql("DELETE FROM dataset_members").update();
    jdbc.sql("DELETE FROM datasets").update();
    jdbc.sql("DELETE FROM users").update();
  }

  void start(Slow slow) {
    start(new WorkerProperties(1, null, null, null, null), new Fake(Type.PROBE, false, slow));
  }

  void start(WorkerProperties props, Fake... fakes) {
    if (notifications == null) notifications = new JobNotifications(db);
    var source = new JobService(queue, jdbc, List.of(fakes), storeCapacity, notifications, BEAT);
    worker = new Worker(source, List.of(fakes), props, BEAT);
    worker.start();
  }

  /**
   * A PROBE job for a new video in dataset {@code name}, with member {@code name} who has a token.
   */
  long probe(String name, boolean active) {
    long dataset =
        jdbc.sql(
                "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                    + " fps, chunk_clips, active) VALUES (?, ?, 12, 5, 30, 50, ?) RETURNING id")
            .params(name, name, active)
            .query(Long.class)
            .single();
    jdbc.sql(
            """
            WITH u AS (INSERT INTO users (box_user_id, login, refresh_token_enc)
                       VALUES (?, 'u', decode('00', 'hex')) RETURNING id)
            INSERT INTO dataset_members (user_id, dataset_id) SELECT id, ? FROM u
            """)
        .params(name, dataset)
        .update();
    long video =
        jdbc.sql(
                "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes)"
                    + " VALUES (?, '1', 'aa', 1) RETURNING id")
            .param(dataset)
            .query(Long.class)
            .single();
    return jdbc.sql(
            "INSERT INTO jobs (type, dataset_id, video_id) VALUES ('PROBE', ?, ?) RETURNING id")
        .params(dataset, video)
        .query(Long.class)
        .single();
  }

  OffsetDateTime leaseUntil(long job) {
    return jdbc.sql("SELECT lease_until FROM jobs WHERE id = ?")
        .param(job)
        .query(OffsetDateTime.class)
        .single();
  }

  /** Runs like a long ffmpeg until cancelled. */
  static void untilCancelled(CountDownLatch cancelled) throws InterruptedException {
    try {
      Thread.sleep(Duration.ofMinutes(1));
    } catch (InterruptedException e) {
      cancelled.countDown();
      throw e;
    }
  }

  String state(long job) {
    return jdbc.sql("SELECT concat_ws(' ', status, attempts, last_error) FROM jobs WHERE id = ?")
        .param(job)
        .query(String.class)
        .optional()
        .orElse("DELETED");
  }

  @Test
  void completesJobsWithTheirResults() {
    long a = probe("a", true);
    long b = probe("b", true);

    start(job -> {});

    await().until(() -> committed.size() == 2);
    assertThat(committed).containsExactlyInAnyOrder(a, b);
    assertThat(state(a)).isEqualTo("DELETED");
    assertThat(state(b)).isEqualTo("DELETED");
  }

  @Test
  void failedAttemptIsRetriedLater() {
    long job = probe("a", true);

    start(j -> {
      throw new IOException("ffmpeg exited 1 for https://dl.box.test/x?token=t");
    });

    // Awaited exactly: the job is PENDING before the claim, too.
    await().until(() -> state(job).equals("PENDING 1 IOException: ffmpeg exited 1 for <url>"));
  }

  @Test
  void onlyActiveDatasetsWithAMemberTokenGetWork() {
    long inactive = probe("a", false);
    long noToken = probe("b", true);
    jdbc.sql("UPDATE users SET refresh_token_enc = NULL WHERE box_user_id = 'b'").update();
    long workable = probe("c", true);

    start(job -> {});

    // Claims go in id order: the other jobs would have been taken first.
    await().until(() -> state(workable).equals("DELETED"));
    assertThat(state(inactive)).isEqualTo("PENDING 0");
    assertThat(state(noToken)).isEqualTo("PENDING 0");
  }

  @Test
  void heartbeatExtendsLease() {
    long job = probe("a", true);
    var leases = new ConcurrentLinkedQueue<OffsetDateTime>();

    start(j -> {
      leases.add(leaseUntil(job));
      Thread.sleep(BEAT.multipliedBy(3));
      leases.add(leaseUntil(job));
    });

    await().until(() -> leases.size() == 2);
    assertThat(leases.poll()).isBefore(leases.poll());
  }

  @Test
  void lostLeaseCancelsHandler() throws Exception {
    long job = probe("a", true);
    var cancelled = new CountDownLatch(1);

    start(j -> {
      // Another worker takes the job over.
      jdbc.sql("UPDATE jobs SET lease_version = lease_version + 1").update();
      untilCancelled(cancelled);
    });

    assertThat(cancelled.await(BEAT.multipliedBy(10).toMillis(), MILLISECONDS)).isTrue();
    // Left to its new owner: neither failed nor completed.
    assertThat(state(job)).isEqualTo("LEASED 1");
  }

  @Test
  void stopReleasesRunningJob() throws Exception {
    long job = probe("a", true);
    var started = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    start(j -> {
      started.countDown();
      untilCancelled(cancelled);
    });
    assertThat(started.await(5, SECONDS)).isTrue();

    worker.stop();

    assertThat(cancelled.getCount()).isZero();
    // Not counted as an attempt, and claimable at once.
    assertThat(state(job)).isEqualTo("PENDING 0");
    assertThat(
            jdbc.sql("SELECT run_after <= now() FROM jobs WHERE id = ?")
                .param(job)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void noUsableMemberToPrepareReleasesJob() {
    long job = probe("a", true);
    var noMember =
        new Fake(Type.PROBE, false, j -> {}) {
          @Override
          public Long prepare(Job j) {
            throw new NoUsableMemberException(j.datasetId());
          }
        };

    start(new WorkerProperties(1, null, null, null, null), noMember);

    // Not counted, and not claimed again before a member may have signed in. Awaited exactly: the
    // job is PENDING 0 before the claim, too.
    await()
        .until(() ->
            "PENDING 0".equals(state(job))
                && jdbc.sql("SELECT run_after > now() FROM jobs WHERE id = ?")
                    .param(job)
                    .query(Boolean.class)
                    .single());
  }

  @Test
  void longPollReturnsAJobAnnouncedMeanwhile() throws Exception {
    notifications = new JobNotifications(db);
    notifications.start();
    var fake = new Fake(Type.PROBE, false, j -> {});
    var source =
        new JobService(
            queue, jdbc, List.of(fake), storeCapacity, notifications, Duration.ofMinutes(1));
    var claim = CompletableFuture.supplyAsync(() -> source.claim("w1", Set.of(Type.PROBE)));
    Thread.sleep(500); // Waiting by now.

    long job = probe("a", true);

    assertThat(claim.get(2, SECONDS).orElseThrow().job().id()).isEqualTo(job);
  }

  @Test
  void failedPrepareWaitsOutTheLongPoll() {
    long first = probe("a", true);
    long second = probe("b", true);
    var boxDown =
        new Fake(Type.PROBE, false, j -> {}) {
          @Override
          public Long prepare(Job j) {
            throw new IllegalStateException("Box 503");
          }
        };
    var wait = Duration.ofMillis(500);
    var source =
        new JobService(
            queue, jdbc, List.of(boxDown), storeCapacity, new JobNotifications(db), wait);

    long started = System.nanoTime();
    assertThat(source.claim("w1", Set.of(Type.PROBE))).isEmpty();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(wait);
    assertThat(state(first)).isEqualTo("PENDING 1 IllegalStateException: Box 503");
    assertThat(state(second)).isEqualTo("PENDING 0"); // Not failed right after.
  }

  @Test
  void stopEndsAWaitingClaim() throws Exception {
    notifications = new JobNotifications(db);
    notifications.start();
    var fake = new Fake(Type.PROBE, false, j -> {});
    var source =
        new JobService(
            queue, jdbc, List.of(fake), storeCapacity, notifications, Duration.ofMinutes(1));
    worker = new Worker(source, List.of(fake), new WorkerProperties(1, null, null, null, null));
    worker.start();
    Thread.sleep(500); // Waiting in the claim by now.

    long started = System.nanoTime();
    worker.stop();

    // Not the minute of the poll, nor the stop timeout.
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void datasetOverQuotaWaitsUntilClipsAreFreed() {
    long full = probe("a", true);
    // 100 bytes stored of a 100-byte quota.
    jdbc.sql(
            "INSERT INTO clips (video_id, clip_index, start_ms, end_ms, object_key, sha256,"
                + " size_bytes) SELECT video_id, 0, 0, 1000, 'k', 'x', 100 FROM jobs WHERE id = ?")
        .param(full)
        .update();
    jdbc.sql("UPDATE datasets SET max_pending_bytes = 100 WHERE name = 'a'").update();
    long open = probe("b", true);

    start(job -> {});

    // Claims go in id order: the full dataset's job would have been taken first.
    await().until(() -> state(open).equals("DELETED"));
    assertThat(state(full)).isEqualTo("PENDING 0");

    // Labeled clips deleted (#134): the quota frees up.
    jdbc.sql("UPDATE clips SET object_key = NULL, sha256 = NULL, size_bytes = NULL").update();
    await().until(() -> state(full).equals("DELETED"));
  }

  @Test
  void fullStoreStopsClippingInEveryDataset() {
    long stored = probe("a", true);
    // 100 bytes stored, under the dataset's quota but at the store's capacity.
    jdbc.sql(
            "INSERT INTO clips (video_id, clip_index, start_ms, end_ms, object_key, sha256,"
                + " size_bytes) SELECT video_id, 0, 0, 1000, 'k', 'x', 100 FROM jobs WHERE id = ?")
        .param(stored)
        .update();
    long other = probe("b", true);
    storeCapacity = 100;

    start(j -> {});

    // Freed: both datasets get work again. Before that, neither did.
    await().during(Duration.ofSeconds(2)).until(committed::isEmpty);
    jdbc.sql("UPDATE clips SET object_key = NULL, sha256 = NULL, size_bytes = NULL").update();
    await().until(() -> committed.containsAll(List.of(stored, other)));
  }

  @Test
  void lowDiskClaimsOnlyJobsThatDoNotWriteToIt() {
    long probe = probe("a", true);
    long chunk =
        jdbc.sql(
                "INSERT INTO jobs (type, dataset_id, video_id, payload) SELECT 'CHUNK',"
                    + " dataset_id, video_id, '{\"chunk\": 0}' FROM jobs WHERE id = ? RETURNING id")
            .param(probe)
            .query(Long.class)
            .single();
    long later = probe("b", true);
    var noDisk = new WorkerProperties(1, null, DataSize.ofTerabytes(1_000_000), null, null);

    start(noDisk, new Fake(Type.PROBE, false, job -> {}), new Fake(Type.CHUNK, true, job -> {}));

    // Claims go in id order: the chunk would have been taken before the later probe.
    await().until(() -> state(later).equals("DELETED"));
    assertThat(state(probe)).isEqualTo("DELETED");
    assertThat(state(chunk)).isEqualTo("PENDING 0");
  }
}
