package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Wakes waiting claims when a job becomes claimable: Postgres {@code NOTIFY jobs} from the jobs
 * trigger. One connection of its own, outside the pool, listens. Lost while reconnecting, so a
 * claim still ends with its long poll.
 */
@Component
@Profile("web")
public class JobNotifications implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(JobNotifications.class);
  private static final Duration RECONNECT = Duration.ofSeconds(5);

  private final JdbcConnectionDetails db;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition announced = lock.newCondition();
  private long generation;
  private volatile boolean running;
  // Stopping: waits end at once, so long-polls answer before shutdown.
  private volatile boolean closed;
  private Thread listener;

  public JobNotifications(JdbcConnectionDetails db) {
    this.db = db;
  }

  /**
   * Returns when a job is announced, {@link #wakeAll} is called, {@code ms} pass, or on stop.
   *
   * @return false once stopped: waiting again would return at once
   */
  public boolean await(long ms) throws InterruptedException {
    lock.lock();
    try {
      long seen = generation;
      long nanos = MILLISECONDS.toNanos(ms);
      while (generation == seen && nanos > 0 && !closed) nanos = announced.awaitNanos(nanos);
      return !closed;
    } finally {
      lock.unlock();
    }
  }

  public void wakeAll() {
    lock.lock();
    try {
      generation++;
      announced.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void start() {
    closed = false;
    running = true;
    listener = Thread.ofVirtual().name("job-notifications").start(this::listen);
  }

  @Override
  public void stop() {
    running = false;
    closed = true;
    wakeAll();
    try {
      listener.join(); // Within a second: the wait below.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void listen() {
    while (running) {
      try (var conn =
          DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
        conn.createStatement().execute("LISTEN jobs");
        var pg = conn.unwrap(PGConnection.class);
        wakeAll(); // Jobs may have come while nobody listened.
        while (running) {
          var notifications = pg.getNotifications(1000);
          if (notifications != null && notifications.length > 0) wakeAll();
        }
      } catch (SQLException e) {
        if (!running) return;
        log.warn("Job notifications down; slots poll until they reconnect", e);
        try {
          Thread.sleep(RECONNECT);
        } catch (InterruptedException interrupted) {
          return;
        }
      }
    }
  }
}
