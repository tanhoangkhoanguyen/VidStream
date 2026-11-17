package io.github.tanhoangkhoanguyen.vidstream.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class VideoCountsIT {

  @Autowired JdbcClient jdbc;

  @Test
  void countsVideosByStatusPerDataset() {
    jdbc.sql(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                + " fps, chunk_clips) VALUES ('a', '1', 12, 5, 30, 50), ('b', '2', 12, 5, 30, 50)")
        .update();
    jdbc.sql(
            "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes, status)"
                + " SELECT d.id, v.file, v.sha1, 1, v.status FROM datasets d,"
                + " (VALUES ('10', 'aa', 'DISCOVERED'), ('11', 'bb', 'DISCOVERED'),"
                + "  ('12', 'aa', 'DUPLICATE')) v (file, sha1, status) WHERE d.name = 'a'")
        .update();
    var counts = new VideoCounts(jdbc);

    assertThat(counts.byStatus("a"))
        .containsExactly(entry("DISCOVERED", 2L), entry("DUPLICATE", 1L));
    assertThat(counts.byStatus("b")).isEmpty();
  }
}
