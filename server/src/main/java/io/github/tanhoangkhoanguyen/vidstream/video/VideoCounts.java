package io.github.tanhoangkhoanguyen.vidstream.video;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** How many videos of a dataset are in each status, for its page. */
@Component
@Profile("web")
public class VideoCounts {

  private final JdbcClient jdbc;

  VideoCounts(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Status → count, by status name; empty before the first scan. */
  public Map<String, Long> byStatus(String dataset) {
    var counts = new LinkedHashMap<String, Long>();
    jdbc.sql(
            "SELECT v.status, count(*) FROM videos v JOIN datasets d ON d.id = v.dataset_id"
                + " WHERE d.name = ? GROUP BY v.status ORDER BY v.status")
        .param(dataset)
        .query(rs -> {
          counts.put(rs.getString(1), rs.getLong(2));
        });
    return counts;
  }
}
