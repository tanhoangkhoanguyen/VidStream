package io.github.tanhoangkhoanguyen.vidstream.video;

import io.github.tanhoangkhoanguyen.vidstream.auth.MemberTokens;
import io.github.tanhoangkhoanguyen.vidstream.auth.NoUsableMemberException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Discovery runs, on demand and daily, with any member's token. An in-memory guard keeps one scan
 * per dataset at a time on this node.
 */
@Component
@Profile("web")
public class VideoScan {

  private static final Logger log = LoggerFactory.getLogger(VideoScan.class);

  interface Scanner {
    void scan(long datasetId, String folderId);
  }

  private record Target(long id, String folder) {}

  private final JdbcClient jdbc;
  private final Scanner scanner;
  private final Set<Long> running = ConcurrentHashMap.newKeySet();

  @Autowired
  VideoScan(JdbcClient jdbc, MemberTokens members, VideoDiscovery discovery) {
    this(jdbc, (id, folder) -> members.call(id, token -> discovery.discover(id, folder, token)));
  }

  VideoScan(JdbcClient jdbc, Scanner scanner) {
    this.jdbc = jdbc;
    this.scanner = scanner;
  }

  /**
   * Starts scanning an active dataset in the background.
   *
   * @return false if it is unknown, inactive or already being scanned
   */
  public boolean start(String dataset) {
    var target =
        jdbc.sql("SELECT id, box_folder_id AS folder FROM datasets WHERE name = ? AND active")
            .param(dataset)
            .query(Target.class)
            .optional();
    if (target.isEmpty() || !running.add(target.get().id())) return false;
    Thread.ofVirtual().name("scan-" + target.get().id()).start(() -> run(target.get()));
    return true;
  }

  /** Every active dataset: new videos appear without a member asking. */
  public void scanActive() {
    jdbc.sql("SELECT name FROM datasets WHERE active")
        .query(String.class)
        .list()
        .forEach(this::start);
  }

  private void run(Target target) {
    long id = target.id();
    try {
      scanner.scan(id, target.folder());
    } catch (NoUsableMemberException e) {
      log.warn("Dataset {}: scan skipped, a member must sign in", id);
    } catch (RuntimeException e) {
      log.error("Dataset {}: scan failed", id, e);
    } finally {
      running.remove(id);
    }
  }
}
