package io.github.tanhoangkhoanguyen.vidstream.worker;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.queue.StaleLeaseException;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Assignment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;

/**
 * Runs jobs on {@code slots} virtual threads: claim from the {@link JobSource}, execute, complete
 * or fail. The claim waits in the source (a long poll), so a slot claims again at once. The
 * execution runs on its own thread while the slot keeps the lease alive. Nodes coordinate only
 * through the job source.
 */
@Component
@Profile("worker")
public class Worker implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(Worker.class);

  /** With the job source unreachable, a slot retries after this long, doubling up to the max. */
  static final Duration MIN_RETRY = Duration.ofSeconds(1);

  static final Duration MAX_RETRY = Duration.ofSeconds(30);

  /** A few beats per lease: one missed beat does not lose the job. */
  static final Duration HEARTBEAT = JobQueue.LEASE.dividedBy(3);

  /**
   * Past a waiting claim's end ({@code vidstream.dispatch.claim-wait}, 20 s by default), within
   * Docker's stop grace period (compose {@code stop_grace_period}, 30 s).
   */
  static final Duration STOP_TIMEOUT = Duration.ofSeconds(25);

  private final JobSource source;
  private final Map<Type, JobExecutor<?, ?>> executors;
  private final Map<Type, JavaType> workTypes;
  private final int slots;
  private final Path workDir;
  private final long minFreeBytes;
  private final Duration heartbeat;
  private final String host = hostname();
  private final List<Thread> threads = new ArrayList<>();
  private final Set<Future<?>> inFlight = ConcurrentHashMap.newKeySet();
  private volatile boolean running;
  private volatile CountDownLatch stopped;

  @Autowired
  Worker(JobSource source, List<JobExecutor<?, ?>> executors, WorkerProperties properties) {
    this(source, executors, properties, HEARTBEAT);
  }

  Worker(
      JobSource source,
      List<JobExecutor<?, ?>> executors,
      WorkerProperties properties,
      Duration heartbeat) {
    this.source = source;
    // Two executors for one type fail startup.
    this.executors = executors.stream().collect(toMap(JobExecutor::type, identity()));
    this.workTypes =
        executors.stream()
            .collect(toMap(JobExecutor::type, e -> WireJson.typeArgument(e, JobExecutor.class, 0)));
    this.slots = properties.slots();
    this.workDir = properties.workDir();
    this.minFreeBytes = properties.minFree().toBytes();
    this.heartbeat = heartbeat;
  }

  @Override
  public void start() {
    try {
      Files.createDirectories(workDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    running = true;
    stopped = new CountDownLatch(1);
    for (int slot = 0; slot < slots; slot++) {
      var id = host + "-" + slot;
      threads.add(Thread.ofVirtual().name("worker-" + slot).start(() -> loop(id)));
    }
  }

  /**
   * Claims nothing new; cancels running jobs and releases them, so other workers take them. A
   * waiting claim is left to end: cancelled, the server would still lease it a job, stuck until the
   * lease expires; a job it returns is released.
   */
  @Override
  public void stop() {
    running = false;
    stopped.countDown();
    inFlight.forEach(task -> task.cancel(true));
    var deadline = Instant.now().plus(STOP_TIMEOUT);
    try {
      for (var thread : threads) thread.join(Duration.between(Instant.now(), deadline));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (threads.stream().anyMatch(Thread::isAlive))
      log.warn("Worker stopped with a job still running; its lease will expire");
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void loop(String worker) {
    long retry = MIN_RETRY.toMillis();
    while (running) {
      try {
        source.claim(worker, claimableTypes()).ifPresent(this::process);
        retry = MIN_RETRY.toMillis();
      } catch (RuntimeException e) {
        log.error("Worker {}: job source unreachable", worker, e);
        try {
          // Jitter: slots on all nodes do not retry in step.
          long wait = ThreadLocalRandom.current().nextLong(retry / 2, retry + 1);
          if (stopped.await(wait, MILLISECONDS)) return;
        } catch (InterruptedException ie) {
          return;
        }
        retry = Math.min(retry * 2, MAX_RETRY.toMillis());
      }
    }
  }

  /** All handled types; with the disk low, only those that do not write to it. */
  private Set<Type> claimableTypes() {
    if (workDir.toFile().getUsableSpace() >= minFreeBytes) return executors.keySet();
    return executors.values().stream()
        .filter(h -> !h.usesDisk())
        .map(JobExecutor::type)
        .collect(Collectors.toSet());
  }

  private void process(Assignment assignment) {
    var job = assignment.job();
    var task =
        new FutureTask<JsonNode>(() -> {
          var work = WireJson.MAPPER.convertValue(assignment.work(), workTypes.get(job.type()));
          return WireJson.MAPPER.valueToTree(execute(executors.get(job.type()), job, work));
        });
    inFlight.add(task);
    if (!running) task.cancel(true); // stop() ran during the claim.
    var thread = Thread.ofVirtual().name("job-" + job.id()).start(task);
    try {
      source.complete(job, await(job, task, thread));
    } catch (StaleLeaseException e) {
      lost(job);
    } catch (Exception e) {
      if (task.isCancelled()) {
        // Shutting down: not counted as an attempt; another worker takes it at once.
        transition(job, () -> source.release(job, Duration.ZERO));
      } else {
        // Sanitized: an error may carry a download URL.
        var error = JobQueue.sanitize(e);
        log.warn("Job {} ({}) failed: {}", job.id(), job.type(), error);
        transition(job, () -> source.fail(job, error));
      }
    } finally {
      inFlight.remove(task);
    }
  }

  /** The execution's result. Heartbeats while it runs; a lost lease cancels it. */
  private JsonNode await(Job job, FutureTask<JsonNode> task, Thread thread) throws Exception {
    while (true) {
      try {
        return task.get(heartbeat.toMillis(), MILLISECONDS);
      } catch (ExecutionException e) {
        throw e.getCause() instanceof Exception cause ? cause : e;
      } catch (CancellationException e) {
        thread.join(); // Cancelled by stop(): the slot waits until the execution has stopped.
        throw e;
      } catch (TimeoutException e) {
        try {
          source.heartbeat(job);
        } catch (StaleLeaseException lost) {
          task.cancel(true); // Interrupts the execution, which stops its ffmpeg.
          thread.join(); // The slot stays busy until the execution has stopped.
          throw lost;
        } catch (RuntimeException failed) {
          // DB unreachable: the next beat retries; the lease outlasts a few missed beats.
          log.warn("Job {}: heartbeat failed", job.id(), failed);
        }
      }
    }
  }

  @SuppressWarnings("unchecked") // Read as this executor's work type.
  private static <W> Object execute(JobExecutor<W, ?> executor, Job job, Object work)
      throws Exception {
    return executor.execute(job, (W) work);
  }

  /** A lost lease only logs: the job is no longer ours. */
  private static void transition(Job job, Runnable transition) {
    try {
      transition.run();
    } catch (StaleLeaseException e) {
      lost(job);
    }
  }

  private static void lost(Job job) {
    log.warn("Job {} ({}): lease lost; another worker may own it", job.id(), job.type());
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      throw new IllegalStateException("no hostname for the worker ID", e);
    }
  }
}
