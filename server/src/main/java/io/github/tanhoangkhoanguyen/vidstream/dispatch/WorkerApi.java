package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NO_CONTENT;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.queue.StaleLeaseException;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Assignment;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * The worker API over {@link JobService}. A worker is its token's name plus a slot name it picks
 * ({@code <name>:<slot>}); reports take the lease version only, and a token reports only on its own
 * workers' leases. A lost or foreign lease answers 409; the worker drops the job.
 */
@RestController
@RequestMapping("/api/worker")
@Profile("web")
class WorkerApi {

  private static final Pattern SLOT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  /** A release may hold a job back this long at most. */
  static final Duration MAX_RELEASE_DELAY = Duration.ofHours(1);

  record Claim(String slot, Set<Type> types) {}

  record Lease(int leaseVersion) {}

  record Uploads(int leaseVersion, List<Blob> blobs) {}

  record Completion(int leaseVersion, JsonNode result) {}

  record Failure(int leaseVersion, String error) {}

  record Release(int leaseVersion, long delayMs) {}

  private final JobService jobs;

  WorkerApi(JobService jobs) {
    this.jobs = jobs;
  }

  /**
   * 200 with a job and its work, or 204 when none came within the long poll ({@link
   * JobService#claim}); the worker's HTTP timeout must exceed it.
   */
  @PostMapping("/claim")
  ResponseEntity<Assignment> claim(@AuthenticationPrincipal String name, @RequestBody Claim claim) {
    if (claim.types() == null) throw new ResponseStatusException(BAD_REQUEST, "no types");
    if (claim.slot() == null || !SLOT.matcher(claim.slot()).matches())
      throw new ResponseStatusException(BAD_REQUEST, "slot must match " + SLOT);
    return jobs.claim(worker(name, claim.slot()), claim.types())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/jobs/{id}/heartbeat")
  @ResponseStatus(NO_CONTENT)
  void heartbeat(
      @AuthenticationPrincipal String name, @PathVariable long id, @RequestBody Lease lease) {
    jobs.heartbeat(leased(name, id, lease.leaseVersion()));
  }

  /** Presigned PUTs for the files the job made, in order; 400 for files not the job's. */
  @PostMapping("/jobs/{id}/uploads")
  List<Upload> uploads(
      @AuthenticationPrincipal String name, @PathVariable long id, @RequestBody Uploads u) {
    if (u.blobs() == null) throw new ResponseStatusException(BAD_REQUEST, "no blobs");
    var job = leased(name, id, u.leaseVersion());
    try {
      return jobs.uploads(job, u.blobs());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/jobs/{id}/complete")
  @ResponseStatus(NO_CONTENT)
  void complete(
      @AuthenticationPrincipal String name, @PathVariable long id, @RequestBody Completion done) {
    if (done.result() == null) throw new ResponseStatusException(BAD_REQUEST, "no result");
    jobs.complete(leased(name, id, done.leaseVersion()), done.result());
  }

  @PostMapping("/jobs/{id}/fail")
  @ResponseStatus(NO_CONTENT)
  void fail(@AuthenticationPrincipal String name, @PathVariable long id, @RequestBody Failure f) {
    // Cleaned by the queue: URLs removed, length capped.
    var error = f.error() == null ? "unknown error" : f.error();
    jobs.fail(leased(name, id, f.leaseVersion()), error);
  }

  @PostMapping("/jobs/{id}/release")
  @ResponseStatus(NO_CONTENT)
  void release(
      @AuthenticationPrincipal String name, @PathVariable long id, @RequestBody Release r) {
    if (r.delayMs() < 0 || r.delayMs() > MAX_RELEASE_DELAY.toMillis())
      throw new ResponseStatusException(BAD_REQUEST, "delayMs out of range");
    jobs.release(leased(name, id, r.leaseVersion()), Duration.ofMillis(r.delayMs()));
  }

  @ExceptionHandler(StaleLeaseException.class)
  @ResponseStatus(CONFLICT)
  void stale() {}

  /**
   * As stored, while this token's worker holds it: nothing about the job is taken from the request.
   */
  private Job leased(String name, long id, int leaseVersion) {
    return jobs.leased(id, leaseVersion, worker(name, ""));
  }

  private static String worker(String name, String slot) {
    return name + ":" + slot;
  }
}
