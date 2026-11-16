package io.github.tanhoangkhoanguyen.vidstream.queue;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Job queue in Postgres. Workers on any node coordinate only through the jobs table: a claim takes
 * a lease and a new fencing token ({@code lease_version}) that every later transition must present.
 */
@Repository
public class JobQueue {

  /** Without a heartbeat in time, another worker may claim the job. */
  static final Duration LEASE = Duration.ofMinutes(2);

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

  // An expired lease on the last attempt (the worker crashed) fails the job, keeping who held it.
  // The lease_version bump fences that worker out either way.
  private static final String CLAIM =
      """
      WITH next AS (
        SELECT id, attempts >= :maxAttempts AS exhausted FROM jobs
        WHERE (status = 'PENDING' OR (status = 'LEASED' AND lease_until < now()))
          AND run_after <= now() AND type IN (:types) AND dataset_id IN (:datasets)
        ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
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

  JobQueue(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Leases the oldest claimable job: pending, or leased with an expired lease (its worker died or
   * stalled). SKIP LOCKED lets concurrent claimers pass each other instead of waiting.
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
              .query((rs, i) ->
                  rs.getBoolean("exhausted")
                      ? null
                      : new Job(
                          rs.getLong("id"),
                          Type.valueOf(rs.getString("type")),
                          rs.getLong("dataset_id"),
                          rs.getObject("video_id", Long.class),
                          rs.getString("payload"),
                          rs.getInt("lease_version")))
              .list();
      if (rows.isEmpty()) return Optional.empty();
      // Null: the job was failed instead; try the next one.
      if (rows.getFirst() != null) return Optional.of(rows.getFirst());
    }
  }

  /** Extends the lease; call every {@link #LEASE}/3. */
  public void heartbeat(Job job) {
    transition(
        job,
        "lease_until = now() + :leaseMs * interval '1 millisecond'",
        Map.of("leaseMs", LEASE.toMillis()));
  }

  /** Retries the job after {@link #RETRY_DELAY}, or fails it on the last attempt. */
  public void fail(Job job, Throwable cause) {
    transition(
        job,
        """
        status = CASE WHEN attempts < :maxAttempts THEN 'PENDING' ELSE 'FAILED' END,
        run_after = now() + :delayMs * interval '1 millisecond', last_error = :error\
        """,
        Map.of(
            "maxAttempts", MAX_ATTEMPTS,
            "delayMs", RETRY_DELAY.toMillis(),
            "error", sanitize(cause)));
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
  static String sanitize(Throwable cause) {
    var message = cause.getMessage() == null ? "" : ": " + cause.getMessage();
    var text = URL.matcher(cause.getClass().getSimpleName() + message).replaceAll("<url>");
    return text.length() > 300 ? text.substring(0, 300) : text;
  }

  /** Applies {@code set} only while {@code job}'s lease is current. */
  private void transition(Job job, String set, Map<String, ?> params) {
    int rows =
        jdbc.sql(
                "UPDATE jobs SET "
                    + set
                    + " WHERE id = :id AND lease_version = :version AND status = 'LEASED'")
            .param("id", job.id())
            .param("version", job.leaseVersion())
            .params(params)
            .update();
    if (rows == 0) throw new StaleLeaseException(job.id(), job.leaseVersion());
  }
}
