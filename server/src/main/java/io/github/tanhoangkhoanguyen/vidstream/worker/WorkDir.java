package io.github.tanhoangkhoanguyen.vidstream.worker;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Local scratch of the jobs on this node: one {@code <job>-<lease_version>} dir per attempt. */
@Component
@Profile("worker")
public class WorkDir {

  private static final Logger log = LoggerFactory.getLogger(WorkDir.class);
  private static final Pattern ATTEMPT = Pattern.compile("[0-9]+-[0-9]+");

  /**
   * An attempt's dir untouched this long is a crashed one's: a running CHUNK writes a clip every
   * few seconds.
   */
  static final Duration STALE = Duration.ofHours(1);

  private final Path root;

  @Autowired
  WorkDir(WorkerProperties props) {
    this(props.workDir());
  }

  public WorkDir(Path root) {
    this.root = root;
  }

  /** An empty dir for this attempt. */
  public Path create(Job job) throws IOException {
    var dir = root.resolve(job.id() + "-" + job.leaseVersion());
    delete(dir);
    return Files.createDirectories(dir);
  }

  public static void delete(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (var paths = Files.walk(dir)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  /**
   * Deletes what crashed attempts left behind. By age, not by lease: another worker may share the
   * dir, and the server is not asked.
   */
  @Scheduled(fixedDelayString = "PT1H")
  public void sweep() throws IOException {
    if (!Files.isDirectory(root)) return;
    var cutoff = Instant.now().minus(STALE);
    int deleted = 0;
    try (var dirs = Files.list(root)) {
      for (var dir : dirs.toList()) {
        if (!ATTEMPT.matcher(dir.getFileName().toString()).matches()
            || Files.getLastModifiedTime(dir).toInstant().isAfter(cutoff)) continue;
        delete(dir);
        deleted++;
      }
    }
    if (deleted > 0) log.info("Work dir: {} leftover attempts deleted", deleted);
  }
}
