package io.github.tanhoangkhoanguyen.vidstream.queue;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
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

  public enum Type {
    PROBE,
    CHUNK,
    MERGE,
    RETENTION
  }

  /** A claimed job; {@code leaseVersion} is its fencing token. */
  public record Job(
      long id, Type type, long datasetId, Long videoId, String payload, int leaseVersion) {}

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
    return jdbc.sql(
            """
            UPDATE jobs j SET status = 'LEASED', worker_id = :worker,
              lease_until = now() + :leaseMs * interval '1 millisecond',
              lease_version = j.lease_version + 1, attempts = j.attempts + 1
            WHERE j.id = (
              SELECT id FROM jobs
              WHERE (status = 'PENDING' OR (status = 'LEASED' AND lease_until < now()))
                AND run_after <= now() AND attempts < :maxAttempts
                AND type IN (:types) AND dataset_id IN (:datasets)
              ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING j.id, j.type, j.dataset_id, j.video_id, j.payload, j.lease_version
            """)
        .param("worker", workerId)
        .param("leaseMs", LEASE.toMillis())
        .param("maxAttempts", MAX_ATTEMPTS)
        .param("types", types.stream().map(Type::name).toList())
        .param("datasets", datasets)
        .query((rs, i) ->
            new Job(
                rs.getLong("id"),
                Type.valueOf(rs.getString("type")),
                rs.getLong("dataset_id"),
                rs.getObject("video_id", Long.class),
                rs.getString("payload"),
                rs.getInt("lease_version")))
        .optional();
  }
}
