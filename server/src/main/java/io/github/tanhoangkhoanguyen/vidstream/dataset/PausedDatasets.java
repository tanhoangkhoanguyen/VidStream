package io.github.tanhoangkhoanguyen.vidstream.dataset;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Active datasets that workers take no jobs from until a member signs in to Box. */
@Component
@Profile("web")
public class PausedDatasets {

  private final JdbcClient jdbc;

  PausedDatasets(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** True if the dataset is active but no member has a Box token. */
  public boolean isPaused(String dataset) {
    return jdbc.sql(
            "SELECT d.active AND w.id IS NULL FROM datasets d"
                + " LEFT JOIN workable_datasets w ON w.id = d.id WHERE d.name = ?")
        .param(dataset)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }
}
