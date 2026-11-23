package io.github.tanhoangkhoanguyen.vidstream.worker;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.queue.StaleLeaseException;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Where a worker claims jobs and reports on them: the server, in process until #153's API. */
public interface JobSource {

  /** A claimed job and what its {@link JobExecutor} needs. */
  record Assignment(Job job, JsonNode work) {}

  /** A file the job made, by its index in the job; {@code sha256} in hex. */
  record Blob(int index, long size, String sha256) {}

  /** Empty when no job of these types is claimable. */
  Optional<Assignment> claim(String worker, Set<Type> types);

  /**
   * @throws StaleLeaseException the lease is lost: stop the job
   */
  void heartbeat(Job job);

  /**
   * Where to upload each blob, in order: the server picks the keys, the store takes only these
   * bytes.
   *
   * @throws StaleLeaseException the job is no longer ours
   */
  List<Upload> uploads(Job job, List<Blob> blobs);

  /**
   * Commits the result with the job, only while its lease is held.
   *
   * @throws StaleLeaseException nothing was committed
   */
  void complete(Job job, JsonNode result);

  /**
   * Retried later, or failed on the last attempt.
   *
   * @throws StaleLeaseException the job is no longer ours
   */
  void fail(Job job, String error);

  /**
   * Given back without counting the attempt.
   *
   * @throws StaleLeaseException the job is no longer ours
   */
  void release(Job job, Duration delay);
}
