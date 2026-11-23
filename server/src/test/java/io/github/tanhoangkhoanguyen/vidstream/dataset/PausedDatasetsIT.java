package io.github.tanhoangkhoanguyen.vidstream.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class PausedDatasetsIT {

  @Autowired JdbcClient jdbc;
  PausedDatasets paused;

  @BeforeEach
  void setUp() {
    paused = new PausedDatasets(jdbc);
    jdbc.sql(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                + " fps, chunk_clips) VALUES ('a', '1', 12, 5, 30, 50)")
        .update();
  }

  void member(boolean token) {
    jdbc.sql(
            """
            WITH u AS (INSERT INTO users (box_user_id, login, refresh_token_enc)
                       VALUES (gen_random_uuid(), 'u', CASE WHEN ? THEN decode('00', 'hex') END)
                       RETURNING id)
            INSERT INTO dataset_members (user_id, dataset_id)
            SELECT u.id, d.id FROM u, datasets d WHERE d.name = 'a'
            """)
        .param(token)
        .update();
  }

  @Test
  void pausedWithoutMemberToken() {
    member(false);

    assertThat(paused.isPaused("a")).isTrue();
  }

  @Test
  void runningWithOneMemberToken() {
    member(false);
    member(true);

    assertThat(paused.isPaused("a")).isFalse();
  }

  @Test
  void inactiveOrUnknownIsNotPaused() {
    jdbc.sql("UPDATE datasets SET active = false").update();

    assertThat(paused.isPaused("a")).isFalse();
    assertThat(paused.isPaused("x")).isFalse();
  }
}
