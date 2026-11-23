package io.github.tanhoangkhoanguyen.vidstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import io.github.tanhoangkhoanguyen.vidstream.auth.MemberTokens;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import io.github.tanhoangkhoanguyen.vidstream.media.TestMedia;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.storage.GarageContainer;
import io.github.tanhoangkhoanguyen.vidstream.worker.HttpJobSource;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A worker with only the server's URL and a token clips a video: claims over the API, cuts with
 * ffmpeg, uploads by presigned PUT to Garage, completes. The worker runs as its own application,
 * without a database. Box is stubbed; the video is generated.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "vidstream.role=web",
      "vidstream.datasets-file=classpath:datasets.yml",
      "management.server.port=0",
      "vidstream.dispatch.claim-wait=PT2S",
      "vidstream.dispatch.workers.e2e=" + WorkerEndToEndIT.TOKEN_SHA256
    })
@Import(PostgresTestConfig.class)
class WorkerEndToEndIT {

  static final String TOKEN = "e2e-token";
  static final String TOKEN_SHA256 =
      "cc318af2129b329b084ebdf17f2608bf3d8a7da6082f345cebf205833876fd88";

  static final GarageContainer GARAGE = new GarageContainer();
  @TempDir static Path dir;
  static TestMedia media;

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @MockitoBean MemberTokens members;
  @MockitoBean BoxClient box;

  @DynamicPropertySource
  static void store(DynamicPropertyRegistry registry) throws Exception {
    GARAGE.start();
    var s3 = GARAGE.bootstrap("clips-e2e");
    registry.add("vidstream.s3.endpoint", () -> s3.endpoint().toString());
    registry.add("vidstream.s3.access-key", s3::accessKey);
    registry.add("vidstream.s3.secret-key", s3::secretKey);
  }

  @BeforeAll
  static void video() throws Exception {
    media = new TestMedia(dir).ffmpeg("video.mp4", TestMedia.VIDEO_40S);
  }

  @AfterAll
  static void stop() {
    media.close();
    GARAGE.stop();
  }

  @Test
  void workerWithOnlyUrlAndTokenClipsAVideo() {
    given(members.call(anyLong(), any()))
        .willAnswer(call -> call.<Function<String, ?>>getArgument(1).apply("box-token"));
    given(box.downloadUrl("box-token", "video.mp4")).willReturn(media.url("video.mp4"));
    long user =
        insert(
            "INSERT INTO users (box_user_id, login, refresh_token_enc)"
                + " VALUES ('e2e', 'u', decode('00', 'hex')) RETURNING id");
    long dataset =
        insert(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds,"
                + " min_last_clip_seconds, fps, chunk_clips, active)"
                + " VALUES ('e2e', 'e2e', 10, 5, 30, 2, true) RETURNING id");
    try {
      jdbc.sql("INSERT INTO dataset_members (user_id, dataset_id) VALUES (?, ?)")
          .params(user, dataset)
          .update();
      long video =
          insert(
              "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes)"
                  + " VALUES ("
                  + dataset
                  + ", 'video.mp4', 'aa', 1) RETURNING id");
      jdbc.sql("INSERT INTO jobs (type, dataset_id, video_id) VALUES ('PROBE', ?, ?)")
          .params(dataset, video)
          .update();
      var server = URI.create("http://localhost:" + port);

      try (var worker =
          new SpringApplicationBuilder(VidStreamApplication.class)
              .run(
                  "--vidstream.role=worker",
                  "--vidstream.worker.server=" + server,
                  "--vidstream.worker.token=" + TOKEN,
                  "--vidstream.worker.slots=2",
                  "--vidstream.worker.work-dir=" + dir.resolve("work"),
                  "--management.server.port=0")) {
        await()
            .atMost(Duration.ofMinutes(1))
            .until(() ->
                "CLIPPED"
                    .equals(
                        jdbc.sql("SELECT status FROM videos WHERE id = ?")
                            .param(video)
                            .query(String.class)
                            .single()));
      }

      // Each clip's object is the one its row names, with its size.
      var clips =
          jdbc.sql("SELECT object_key, size_bytes FROM clips WHERE video_id = ?")
              .param(video)
              .query((rs, i) -> new Object[] {rs.getString(1), rs.getLong(2)})
              .list();
      assertThat(clips).hasSize(4);
      for (var clip : clips)
        assertThat((long) GARAGE.read("clips-e2e", (String) clip[0]).length).isEqualTo(clip[1]);

      // Over a real server, an unknown token is refused, not sent to sign-in.
      assertThatThrownBy(() -> new HttpJobSource(server, "unknown").claim("s", Set.of(Type.PROBE)))
          .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    } finally {
      // Other ITs share the database.
      for (var table : new String[] {"units", "clips", "jobs", "videos"})
        jdbc.sql("DELETE FROM " + table).update();
      jdbc.sql("DELETE FROM dataset_members WHERE dataset_id = ?").param(dataset).update();
      jdbc.sql("DELETE FROM datasets WHERE id = ?").param(dataset).update();
      jdbc.sql("DELETE FROM users WHERE id = ?").param(user).update();
    }
  }

  long insert(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
