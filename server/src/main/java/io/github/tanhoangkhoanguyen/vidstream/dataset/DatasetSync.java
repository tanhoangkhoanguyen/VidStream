package io.github.tanhoangkhoanguyen.vidstream.dataset;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.tanhoangkhoanguyen.vidstream.config.VidStreamProperties;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies {@code datasets.yml} at web startup: each listed dataset gets its clip bucket, then its
 * row. Any error stops startup.
 */
@Component
@Profile("web")
class DatasetSync implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DatasetSync.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ClipStore store;
  private final Resource file;

  DatasetSync(JdbcClient jdbc, TransactionTemplate tx, ClipStore store, VidStreamProperties props) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.store = store;
    this.file = Objects.requireNonNull(props.datasetsFile(), "vidstream.datasets-file is not set");
  }

  @Override
  public void run(ApplicationArguments args) throws IOException {
    var specs = DatasetsYaml.parse(file.getContentAsString(UTF_8));
    // Before the rows, outside their transaction: no dataset is clipped without its bucket.
    specs.forEach(spec -> store.ensureBucket(ClipStore.bucket(spec.name())));
    tx.executeWithoutResult(status -> sync(specs));
  }

  /** New datasets are added; folder and clip settings never change; unlisted ones go inactive. */
  void sync(List<DatasetSpec> specs) {
    var stored = new HashMap<String, DatasetSpec>();
    var inactive = new HashSet<String>();
    jdbc.sql(
            "SELECT name, box_folder_id, clip_seconds, min_last_clip_seconds, fps, chunk_clips,"
                + " active FROM datasets")
        .query(rs -> {
          var spec =
              new DatasetSpec(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getInt(3),
                  rs.getInt(4),
                  rs.getInt(5),
                  rs.getInt(6));
          stored.put(spec.name(), spec);
          if (!rs.getBoolean(7)) inactive.add(spec.name());
        });

    int added = 0;
    for (var spec : specs) {
      var old = stored.remove(spec.name());
      if (old == null) {
        insert(spec);
        added++;
      } else if (!old.equals(spec)) {
        throw new IllegalStateException(
            "datasets.yml: dataset '%s' cannot change its folder or clip settings: was %s, now %s"
                .formatted(spec.name(), old, spec));
      } else if (inactive.contains(spec.name())) {
        setActive(spec.name(), true);
      }
    }
    var removed = stored.keySet().stream().filter(name -> !inactive.contains(name)).toList();
    removed.forEach(name -> setActive(name, false));
    log.info("datasets.yml: {} added, {} deactivated", added, removed.size());
  }

  private void insert(DatasetSpec s) {
    jdbc.sql(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds, fps,"
                + " chunk_clips) VALUES (?, ?, ?, ?, ?, ?)")
        .params(
            s.name(),
            s.boxFolderId(),
            s.clipSeconds(),
            s.minLastClipSeconds(),
            s.fps(),
            s.chunkClips())
        .update();
  }

  private void setActive(String name, boolean active) {
    jdbc.sql("UPDATE datasets SET active = ? WHERE name = ?").params(active, name).update();
  }
}
