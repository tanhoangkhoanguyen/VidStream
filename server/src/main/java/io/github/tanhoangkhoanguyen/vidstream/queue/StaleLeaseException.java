package io.github.tanhoangkhoanguyen.vidstream.queue;

/**
 * The lease was lost (expired and reclaimed, or ended): the holder must stop and commit nothing.
 */
public class StaleLeaseException extends RuntimeException {

  public StaleLeaseException(long jobId, int leaseVersion) {
    super("job " + jobId + " lease " + leaseVersion + " is stale");
  }
}
