package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import io.github.tanhoangkhoanguyen.vidstream.auth.NoUsableMemberException;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.queue.StaleLeaseException;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import io.github.tanhoangkhoanguyen.vidstream.storage.S3Properties;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource;
import io.github.tanhoangkhoanguyen.vidstream.worker.WireJson;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;

/**
 * The server's side of workers' jobs: claims with backpressure, prepares what a job needs (the
 * database and Box tokens stay here), and commits results with the fenced completion. Workers reach
 * it through the worker API.
 */
@Service
@Profile("web")
public class JobService implements JobSource {

  private static final Logger log = LoggerFactory.getLogger(JobService.class);

  /** No member could prepare the job: tried again once one may have signed in. */
  static final Duration NO_MEMBER_RETRY = Duration.ofMinutes(1);

  private final JobQueue queue;
  private final JdbcClient jdbc;
  private final Map<Type, JobType<?, ?>> jobTypes;
  private final Map<Type, JavaType> resultTypes;
  private final long storeCapacity;
  private final JobNotifications notifications;
  private final Duration claimWait;

  @Autowired
  JobService(
      JobQueue queue,
      JdbcClient jdbc,
      List<JobType<?, ?>> types,
      S3Properties store,
      JobNotifications notifications,
      @Value("${vidstream.dispatch.claim-wait:PT20S}") Duration claimWait) {
    this(queue, jdbc, types, store.capacity().toBytes(), notifications, claimWait);
  }

  public JobService(
      JobQueue queue,
      JdbcClient jdbc,
      List<JobType<?, ?>> types,
      long storeCapacity,
      JobNotifications notifications,
      Duration claimWait) {
    this.queue = queue;
    this.jdbc = jdbc;
    // Two of one type fail startup.
    this.jobTypes = types.stream().collect(toMap(JobType::type, identity()));
    this.resultTypes =
        types.stream()
            .collect(toMap(JobType::type, t -> WireJson.typeArgument(t, JobType.class, 1)));
    this.storeCapacity = storeCapacity;
    this.notifications = notifications;
    this.claimWait = claimWait;
  }

  /**
   * A long poll: waits up to {@code claim-wait} for a job, so an idle worker gets one within a
   * second of its NOTIFY, without polling. The wait holds no DB connection. Empty when none came,
   * on interrupt, or after a job could not be prepared.
   */
  @Override
  public Optional<Assignment> claim(String worker, Set<Type> types) {
    // Only types this server can prepare: a remote worker may ask for others.
    var known = types.stream().filter(jobTypes::containsKey).toList();
    long deadline = System.nanoTime() + claimWait.toNanos();
    try {
      while (true) {
        var claimed = queue.claim(worker, known, clippableDatasets());
        if (claimed.isPresent()) {
          var job = claimed.get();
          try {
            var work = jobTypes.get(job.type()).prepare(job);
            return Optional.of(new Assignment(job, WireJson.MAPPER.valueToTree(work)));
          } catch (NoUsableMemberException e) {
            // Not failed: no attempt is used up while no member has a token. The next job may be
            // another dataset's.
            log.warn(
                "Job {} ({}): waiting for a member of dataset {} to sign in",
                job.id(),
                job.type(),
                job.datasetId());
            settle(job, () -> queue.release(job, NO_MEMBER_RETRY));
            continue;
          } catch (RuntimeException e) {
            var error = JobQueue.sanitize(e);
            log.warn("Job {} ({}) not prepared: {}", job.id(), job.type(), error);
            settle(job, () -> queue.fail(job, error));
            // E.g. Box down: wait out the poll instead of failing the next job at once.
            long left = deadline - System.nanoTime();
            if (left > 0) NANOSECONDS.sleep(left);
            return Optional.empty();
          }
        }
        long left = deadline - System.nanoTime();
        if (left <= 0 || !notifications.await(NANOSECONDS.toMillis(left) + 1))
          return Optional.empty();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /**
   * @see JobQueue#leased
   */
  public Job leased(long id, int leaseVersion, String workerPrefix) {
    return queue.leased(id, leaseVersion, workerPrefix);
  }

  @Override
  public void heartbeat(Job job) {
    queue.heartbeat(job);
  }

  @Override
  public List<Upload> uploads(Job job, List<Blob> blobs) {
    return jobTypes.get(job.type()).uploads(job, blobs);
  }

  @Override
  public void complete(Job job, JsonNode result) {
    // Read before the transaction: a malformed result fails without holding a connection.
    Object read = WireJson.MAPPER.convertValue(result, resultTypes.get(job.type()));
    queue.complete(job, () -> commit(jobTypes.get(job.type()), job, read));
  }

  @Override
  public void fail(Job job, String error) {
    queue.fail(job, error);
  }

  @Override
  public void release(Job job, Duration delay) {
    queue.release(job, delay);
  }

  /** Workable and under quota, none while the store is full; only PROBE and CHUNK exist yet. */
  private List<Long> clippableDatasets() {
    return jdbc.sql(
            "SELECT id FROM clippable_datasets"
                + " WHERE (SELECT sum(stored_bytes) FROM datasets) < ?")
        .param(storeCapacity)
        .query(Long.class)
        .list();
  }

  @SuppressWarnings("unchecked") // Read as this type's result type.
  private static <R> void commit(JobType<?, R> type, Job job, Object result) {
    type.commit(job, (R) result);
  }

  /** A lost lease only logs: the job is no longer ours. */
  private static void settle(Job job, Runnable transition) {
    try {
      transition.run();
    } catch (StaleLeaseException e) {
      log.warn("Job {} ({}): lease lost; another worker may own it", job.id(), job.type());
    }
  }
}
