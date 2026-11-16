package io.github.tanhoangkhoanguyen.vidstream.queue;

import static io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type.CHUNK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Not rolled back: commit and rollback are what is tested. Cleaned up after each test.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobQueueCommitIT extends JobQueueSupport {

  @Autowired DataSource dataSource;

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM jobs WHERE dataset_id = ?").param(dataset).update();
    jdbc.sql("DELETE FROM videos WHERE dataset_id = ?").param(dataset).update();
    jdbc.sql("DELETE FROM datasets WHERE id = ?").param(dataset).update();
  }

  int jobCount() {
    return jdbc.sql("SELECT count(*) FROM jobs").query(Integer.class).single();
  }

  @Test
  void completeCommitsWorkWithTheJob() {
    var id = enqueue(CHUNK, 0);

    queue.complete(claim("w1").orElseThrow(), () -> enqueue(CHUNK, 1));

    assertThat(state(id)).isEqualTo("DONE w1 1");
    assertThat(jobCount()).isEqualTo(2);
  }

  @Test
  void failedWorkLeavesJobLeased() {
    var id = enqueue(CHUNK, 0);
    var job = claim("w1").orElseThrow();

    assertThatThrownBy(() ->
            queue.complete(
                job,
                () -> {
                  enqueue(CHUNK, 1);
                  throw new IllegalStateException("ffprobe failed");
                }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(state(id)).isEqualTo("LEASED w1 1");
    assertThat(jobCount()).isEqualTo(1);
  }

  @Test
  void staleWorkerCommitsNothing() {
    var id = enqueue(CHUNK, 0);
    var stale = claim("w1").orElseThrow();
    update("lease_until = now() - interval '1 second'");
    var current = claim("w2").orElseThrow();

    assertThatThrownBy(() -> queue.complete(stale, () -> enqueue(CHUNK, 1)))
        .isInstanceOf(StaleLeaseException.class);
    assertThat(jobCount()).isEqualTo(1);

    queue.complete(current, () -> {});
    assertThat(state(id)).isEqualTo("DONE w2 2");
  }

  /** Many workers on many nodes: each claimer has its own connection. Prints a benchmark line. */
  @Test
  void concurrentClaimersTakeEachJobExactlyOnce() throws Exception {
    int jobs = 5_000;
    int claimers = 100;
    jdbc.sql(
            "INSERT INTO jobs (type, dataset_id, video_id, payload)"
                + " SELECT 'CHUNK', ?, ?, jsonb_build_object('chunk', g) FROM generate_series(1, ?) g")
        .params(dataset, video, jobs)
        .update();

    var claimed = new ConcurrentLinkedQueue<Long>();
    var latencies = new ConcurrentLinkedQueue<Long>();
    long start;
    long elapsed;
    try (var pool = new HikariDataSource()) {
      var source = (HikariDataSource) dataSource;
      pool.setJdbcUrl(source.getJdbcUrl());
      pool.setUsername(source.getUsername());
      pool.setPassword(source.getPassword());
      pool.setMaximumPoolSize(claimers);
      var workers = new JobQueue(JdbcClient.create(pool), new DataSourceTransactionManager(pool));
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < claimers; i++) {
        var worker = "w" + i;
        tasks.add(() -> {
          while (true) {
            long t = System.nanoTime();
            var job = workers.claim(worker, List.of(CHUNK), List.of(dataset));
            latencies.add(System.nanoTime() - t);
            if (job.isEmpty()) return null;
            claimed.add(job.get().id());
            workers.complete(job.get(), () -> {});
          }
        });
      }
      start = System.nanoTime();
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var result : executor.invokeAll(tasks)) result.get();
      }
      elapsed = System.nanoTime() - start;
    }

    assertThat(claimed).hasSize(jobs).doesNotHaveDuplicates();
    assertThat(
            jdbc.sql("SELECT count(*) FROM jobs WHERE status = 'DONE'").query(Long.class).single())
        .isEqualTo(jobs);
    var sorted = latencies.stream().sorted().toList();
    System.out.printf(
        "BENCHMARK claim+complete: %d jobs, %d claimers: %.0f jobs/s; claim p50 %.1f ms, p99 %.1f"
            + " ms%n",
        jobs,
        claimers,
        jobs / (elapsed / 1e9),
        sorted.get(sorted.size() / 2) / 1e6,
        sorted.get(sorted.size() * 99 / 100) / 1e6);
  }
}
