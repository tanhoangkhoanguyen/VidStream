package io.github.tanhoangkhoanguyen.vidstream.periodic;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Replicas claim on their own connections: nothing is rolled back, so each test cleans up.
@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulesIT {

  static final Duration HOUR = Duration.ofHours(1);

  @Autowired JdbcClient jdbc;

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM schedules").update();
  }

  @Test
  void oneOfManyReplicasClaimsARun() throws Exception {
    var schedules = new Schedules(jdbc);
    try (var replicas = Executors.newVirtualThreadPerTaskExecutor()) {
      var claims =
          replicas.invokeAll(
              IntStream.range(0, 8)
                  .mapToObj(i -> (Callable<Boolean>) () -> schedules.claim("sweep", HOUR))
                  .toList());
      int won = 0;
      for (var claim : claims) if (claim.get()) won++;
      assertThat(won).isOne();
    }
  }

  @Test
  void nextRunIsClaimedOncePeriodHasPassed() {
    var schedules = new Schedules(jdbc);
    assertThat(schedules.claim("sweep", HOUR)).isTrue();
    assertThat(schedules.claim("sweep", HOUR)).isFalse();
    assertThat(schedules.claim("scan", HOUR)).isTrue(); // Another schedule.

    // A replica whose timer fires a little early, then one an hour later.
    jdbc.sql("UPDATE schedules SET last_run_at = now() - interval '50 minutes'").update();
    assertThat(schedules.claim("sweep", HOUR)).isFalse();
    jdbc.sql("UPDATE schedules SET last_run_at = now() - interval '1 hour'").update();
    assertThat(schedules.claim("sweep", HOUR)).isTrue();
  }
}
