package io.github.tanhoangkhoanguyen.vidstream.periodic;

import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Runs of periodic work, so that one web replica does each. */
@Component
@Profile("web")
class Schedules {

  private final JdbcClient jdbc;

  Schedules(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Claims a run of {@code name}, done every {@code period}: true for the first replica to ask
   * since 9/10 of a period, which allows for clock skew and timer drift. One statement: replicas
   * asking at once take turns on the row.
   */
  boolean claim(String name, Duration period) {
    return jdbc.sql(
            """
            INSERT INTO schedules (name, last_run_at) VALUES (:name, now())
            ON CONFLICT (name) DO UPDATE SET last_run_at = now()
              WHERE schedules.last_run_at < now() - :gapMs * interval '1 millisecond'
            RETURNING name
            """)
        .param("name", name)
        .param("gapMs", period.multipliedBy(9).dividedBy(10).toMillis())
        .query(String.class)
        .optional()
        .isPresent();
  }
}
