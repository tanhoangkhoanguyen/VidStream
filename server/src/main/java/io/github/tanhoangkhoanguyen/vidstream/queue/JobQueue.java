package io.github.tanhoangkhoanguyen.vidstream.queue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job queue in Postgres. Workers on any node coordinate only through the jobs table: a claim takes
 * a lease and a new fencing token ({@code lease_version}) that every later transition must present.
 */
@Repository
@Profile("web")
public class JobQueue {

  /** Without a heartbeat in time, another worker may claim the job. */
  public static final Duration LEASE = Duration.ofMinutes(2);

  /** Claims per job, crashed attempts included: 1 try + 2 retries. */
  static final int MAX_ATTEMPTS = 3;

  /** A failed attempt is retried after this. */
  static final Duration RETRY_DELAY = Duration.ofMinutes(1);

  // Download URLs carry access tokens; they never reach the database.
  private static final Pattern URL = Pattern.compile("\\S+://\\S+");

  public enum Type {
    PROBE,
    CHUNK,
    MERGE,
    RETENTION
  }

  /** A claimed job; {@code leaseVersion} is its fencing token. */
  public record Job(
      long id, Type type, long datasetId, Long videoId, String payload, int leaseVersion) {}

  // Fair share: the dataset with the fewest leased jobs goes first, then the oldest job; datasets
  // tie when slots are free. Each dataset's head comes from jobs_open, so the cost grows with the
  // datasets, not the jobs. BETWEEN, not =: with =, the planner may walk the primary key in id
  // order
  // instead (it assumes datasets spread evenly over ids) and scan a paused dataset's jobs.
  // An expired lease on the last attempt (the worker crashed) fails the job, keeping who held it.
  // The lease_version bump fences that worker out either way.
  private static final String CLAIM =
      """
      WITH leased AS (
        SELECT dataset_id, count(*) AS n FROM jobs WHERE status = 'LEASED' GROUP BY dataset_id),
      next AS (
        SELECT j.id, j.attempts >= :maxAttempts AS exhausted
        FROM datasets d LEFT JOIN leased l ON l.dataset_id = d.id
        CROSS JOIN LATERAL (
          SELECT id, attempts FROM jobs
          WHERE dataset_id BETWEEN d.id AND d.id
            AND (status = 'PENDING' OR (status = 'LEASED' AND lease_until < now()))
            AND run_after <= now() AND type IN (:types)
          ORDER BY dataset_id, id LIMIT 1 FOR UPDATE SKIP LOCKED) j
        WHERE d.id IN (:datasets)
        ORDER BY coalesce(l.n, 0), j.id LIMIT 1)
      UPDATE jobs j SET
        status = CASE WHEN n.exhausted THEN 'FAILED' ELSE 'LEASED' END,
        worker_id = CASE WHEN n.exhausted THEN j.worker_id ELSE :worker END,
        last_error = CASE WHEN n.exhausted THEN 'lease expired on the last attempt'
                          ELSE j.last_error END,
        lease_until = now() + :leaseMs * interval '1 millisecond',
        lease_version = j.lease_version + 1,
        attempts = least(j.attempts + 1, :maxAttempts)
      FROM next n WHERE j.id = n.id
      RETURNING j.id, j.type, j.dataset_id, j.video_id, j.payload, j.lease_version, n.exhausted
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  JobQueue(JdbcClient jdbc, PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * Leases a claimable job: pending, or leased with an expired lease (its worker died or stalled).
   * Datasets share the workers fairly. SKIP LOCKED lets concurrent claimers pass each other instead
   * of waiting.
   *
   * @param datasets datasets with room for more work (backpressure)
   */
  public Optional<Job> claim(String workerId, Collection<Type> types, Collection<Long> datasets) {
    if (types.isEmpty() || datasets.isEmpty()) return Optional.empty();
    while (true) {
      var rows =
          jdbc.sql(CLAIM)
              .param("worker", workerId)
              .param("leaseMs", LEASE.toMillis())
              .param("maxAttempts", MAX_ATTEMPTS)
              .param("types", types.stream().map(Type::name).toList())
              .param("datasets", datasets)
              .query((rs, i) -> rs.getBoolean("exhausted") ? null : job(rs))
              .list();
      if (rows.isEmpty()) return Optional.empty();
      // Null: the job was failed instead; try the next one.
      if (rows.getFirst() != null) return Optional.of(rows.getFirst());
    }
  }

  /**
   * The job as stored, while a worker whose ID starts with {@code workerPrefix} holds it under
   * {@code leaseVersion}: what a remote worker reports on is read here, not taken from its request.
   *
   * @throws StaleLeaseException the lease is no longer such a worker's
   */
  public Job leased(long id, int leaseVersion, String workerPrefix) {
    return jdbc.sql(
            "SELECT id, type, dataset_id, video_id, payload, lease_version FROM jobs"
                + " WHERE id = ? AND lease_version = ? AND status = 'LEASED'"
                + " AND starts_with(worker_id, ?)")
        .params(id, leaseVersion, workerPrefix)
        .query((rs, i) -> job(rs))
        .optional()
        .orElseThrow(() -> new StaleLeaseException(id, leaseVersion));
  }

  /** Extends the lease; call every {@link #LEASE}/3. */
  public void heartbeat(Job job) {
    transition(
        job,
        "lease_until = now() + :leaseMs * interval '1 millisecond'",
        Map.of("leaseMs", LEASE.toMillis()));
  }

  /**
   * Deletes the finished job and runs {@code work} (e.g. insert its results) in the same
   * transaction, so results commit only with a current lease. The fenced delete goes first: the row
   * stays locked, so no claim can take the job while {@code work} runs. If {@code work} throws,
   * nothing commits. Finished jobs are not kept: results live in their own tables.
   */
  public void complete(Job job, Runnable work) {
    tx.executeWithoutResult(status -> {
      fenced(job, "DELETE FROM jobs", Map.of());
      work.run();
    });
  }

  /**
   * Retries the job after {@link #RETRY_DELAY}, or fails it on the last attempt.
   *
   * @param error stored with URLs removed (they may carry tokens), at most 300 chars
   */
  public void fail(Job job, String error) {
    transition(
        job,
        """
        status = CASE WHEN attempts < :maxAttempts THEN 'PENDING' ELSE 'FAILED' END,
        run_after = now() + :delayMs * interval '1 millisecond', last_error = :error\
        """,
        Map.of(
            "maxAttempts", MAX_ATTEMPTS,
            "delayMs", RETRY_DELAY.toMillis(),
            "error", clean(error)));
  }

  /** Gives the job back without counting the attempt, e.g. on shutdown or with no usable token. */
  public void release(Job job, Duration delay) {
    transition(
        job,
        "status = 'PENDING', attempts = attempts - 1,"
            + " run_after = now() + :delayMs * interval '1 millisecond'",
        Map.of("delayMs", delay.toMillis()));
  }

  /** Class and message, URLs removed, at most 300 chars. */
  public static String sanitize(Throwable cause) {
    var message = cause.getMessage() == null ? "" : ": " + cause.getMessage();
    return clean(cause.getClass().getSimpleName() + message);
  }

  private static String clean(String error) {
    var text = URL.matcher(error).replaceAll("<url>");
    return text.length() > 300 ? text.substring(0, 300) : text;
  }

  private static Job job(ResultSet rs) throws SQLException {
    return new Job(
        rs.getLong("id"),
        Type.valueOf(rs.getString("type")),
        rs.getLong("dataset_id"),
        rs.getObject("video_id", Long.class),
        rs.getString("payload"),
        rs.getInt("lease_version"));
  }

  private void transition(Job job, String set, Map<String, ?> params) {
    fenced(job, "UPDATE jobs SET " + set, params);
  }

  /** Runs {@code statement} only while {@code job}'s lease is current. */
  private void fenced(Job job, String statement, Map<String, ?> params) {
    int rows =
        jdbc.sql(statement + " WHERE id = :id AND lease_version = :version AND status = 'LEASED'")
            .param("id", job.id())
            .param("version", job.leaseVersion())
            .params(params)
            .update();
    if (rows == 0) throw new StaleLeaseException(job.id(), job.leaseVersion());
  }
}
