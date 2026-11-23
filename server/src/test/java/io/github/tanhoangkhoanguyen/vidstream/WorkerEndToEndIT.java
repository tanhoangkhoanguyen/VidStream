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
import io.github.tanhoangkhoanguyen.vidstream.queue.StaleLeaseException;
import io.github.tanhoangkhoanguyen.vidstream.storage.GarageContainer;
import io.github.tanhoangkhoanguyen.vidstream.worker.HttpJobSource;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

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
      "vidstream.dispatch.claim-wait=PT" + WorkerEndToEndIT.CLAIM_WAIT_S + "S",
      "vidstream.dispatch.workers.e2e=" + WorkerEndToEndIT.TOKEN_SHA256
    })
@Import(PostgresTestConfig.class)
class WorkerEndToEndIT {

  static final int CLAIM_WAIT_S = 2;
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
  URI server;
  long user;
  long dataset;

  @DynamicPropertySource
  static void store(DynamicPropertyRegistry registry) throws Exception {
    GARAGE.start();
    var s3 = GARAGE.bootstrap("clips-e2e");
    registry.add("vidstream.s3.endpoint", () -> s3.endpoint().toString());
    registry.add("vidstream.s3.access-key", s3::accessKey);
    registry.add("vidstream.s3.secret-key", s3::secretKey);
  }

  @BeforeAll
  static void generateVideo() throws Exception {
    media = new TestMedia(dir).ffmpeg("video.mp4", TestMedia.VIDEO_40S);
  }

  @AfterAll
  static void stop() {
    media.close();
    GARAGE.stop();
  }

  @BeforeEach
  void setUp() {
    given(members.call(anyLong(), any()))
        .willAnswer(call -> call.<Function<String, ?>>getArgument(1).apply("box-token"));
    given(box.downloadUrl("box-token", "video.mp4")).willReturn(media.url("video.mp4"));
    server = URI.create("http://localhost:" + port);
    user =
        insert(
            "INSERT INTO users (box_user_id, login, refresh_token_enc)"
                + " VALUES ('e2e', 'u', decode('00', 'hex')) RETURNING id");
    dataset =
        insert(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds,"
                + " min_last_clip_seconds, fps, chunk_clips, active)"
                + " VALUES ('e2e', 'e2e', 10, 5, 30, 2, true) RETURNING id");
    jdbc.sql("INSERT INTO dataset_members (user_id, dataset_id) VALUES (?, ?)")
        .params(user, dataset)
        .update();
  }

  /** Other ITs share the database. */
  @AfterEach
  void cleanUp() throws InterruptedException {
    // A closed worker's claim still waits on the web until claim-wait ends; the next test's job
    // would be leased to it.
    Thread.sleep(Duration.ofSeconds(CLAIM_WAIT_S).plusMillis(500));
    for (var table : new String[] {"units", "clips", "jobs", "videos"})
      jdbc.sql("DELETE FROM " + table).update();
    jdbc.sql("DELETE FROM dataset_members WHERE dataset_id = ?").param(dataset).update();
    jdbc.sql("DELETE FROM datasets WHERE id = ?").param(dataset).update();
    jdbc.sql("DELETE FROM users WHERE id = ?").param(user).update();
  }

  @Test
  void workerWithOnlyUrlAndTokenClipsAVideo() {
    long video = video();
    jdbc.sql("INSERT INTO jobs (type, dataset_id, video_id) VALUES ('PROBE', ?, ?)")
        .params(dataset, video)
        .update();

    try (var worker = worker()) {
      awaitClipped(video);
    }

    assertClipsStored(video);
    // Over a real server, an unknown token is refused, not sent to sign-in.
    assertThatThrownBy(() -> new HttpJobSource(server, "unknown").claim("s", Set.of(Type.PROBE)))
        .isInstanceOf(HttpClientErrorException.Unauthorized.class);
  }

  /** As if its machine were killed: no release, no report; only the lease ends its claim. */
  @Test
  void chunkOfAWorkerKilledMidwayIsRedoneOnceItsLeaseExpires() throws Exception {
    long video = video();
    jdbc.sql("UPDATE videos SET duration_ms = 40000, status = 'PROBED'").update();
    jdbc.sql(
            "INSERT INTO clips (video_id, clip_index, start_ms, end_ms)"
                + " SELECT ?, i, i * 10000, (i + 1) * 10000 FROM generate_series(0, 3) i")
        .param(video)
        .update();
    jdbc.sql(
            "INSERT INTO jobs (type, dataset_id, video_id, payload) SELECT 'CHUNK', ?, ?,"
                + " jsonb_build_object('chunk', c, 'first', 2 * c, 'last', 2 * c + 1)"
                + " FROM generate_series(0, 1) c")
        .params(dataset, video)
        .update();

    // The killed worker claimed a chunk and uploaded one of its clips.
    var killed = new HttpJobSource(server, TOKEN);
    var job = killed.claim("killed", Set.of(Type.CHUNK)).orElseThrow().job();
    int clip = new JsonMapper().readTree(job.payload()).get("first").asInt();
    byte[] partial = new byte[1000];
    var upload =
        killed.uploads(job, List.of(new Blob(clip, partial.length, sha256(partial)))).getFirst();
    var put = HttpRequest.newBuilder(upload.url()).PUT(BodyPublishers.ofByteArray(partial));
    upload.headers().forEach(put::header);
    try (var http = HttpClient.newHttpClient()) {
      assertThat(http.send(put.build(), BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }
    String orphan = upload.url().getPath().substring("/clips-e2e/".length());

    try (var worker = worker()) {
      // The other chunk is done; the killed worker's lease still holds its own.
      await()
          .atMost(Duration.ofMinutes(1))
          .until(() -> jdbc.sql("SELECT count(*) FROM jobs").query(Long.class).single() == 1);
      assertThat(
              jdbc.sql("SELECT worker_id || ' ' || lease_version FROM jobs")
                  .query(String.class)
                  .single())
          .isEqualTo("e2e:killed 1");

      jdbc.sql("UPDATE jobs SET lease_until = now() - interval '1 second'").update();
      awaitClipped(video);
    }

    assertClipsStored(video);
    assertThat(jdbc.sql("SELECT count(*) FROM units").query(Long.class).single()).isEqualTo(4);
    // Its upload is not a clip's (the sweep deletes it), and its late report is refused.
    assertThat(jdbc.sql("SELECT object_key FROM clips").query(String.class).list())
        .doesNotContain(orphan);
    assertThat(GARAGE.read("clips-e2e", orphan)).hasSize(partial.length);
    assertThatThrownBy(() -> killed.complete(job, new JsonMapper().createArrayNode()))
        .isInstanceOf(StaleLeaseException.class);
  }

  ConfigurableApplicationContext worker() {
    return new SpringApplicationBuilder(VidStreamApplication.class)
        .run(
            "--vidstream.role=worker",
            "--vidstream.worker.server=" + server,
            "--vidstream.worker.token=" + TOKEN,
            "--vidstream.worker.slots=2",
            "--vidstream.worker.work-dir=" + dir.resolve("work"),
            "--management.server.port=0");
  }

  long video() {
    return jdbc.sql(
            "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes)"
                + " VALUES (?, 'video.mp4', 'aa', 1) RETURNING id")
        .param(dataset)
        .query(Long.class)
        .single();
  }

  void awaitClipped(long video) {
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

  /** Each clip's object is the one its row names, with its size. */
  void assertClipsStored(long video) {
    var clips =
        jdbc.sql("SELECT object_key, size_bytes FROM clips WHERE video_id = ?")
            .param(video)
            .query((rs, i) -> new Object[] {rs.getString(1), rs.getLong(2)})
            .list();
    assertThat(clips).hasSize(4);
    for (var clip : clips)
      assertThat((long) GARAGE.read("clips-e2e", (String) clip[0]).length).isEqualTo(clip[1]);
  }

  static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  long insert(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
