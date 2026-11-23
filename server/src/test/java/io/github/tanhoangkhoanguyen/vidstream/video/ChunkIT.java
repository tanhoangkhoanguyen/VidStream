package io.github.tanhoangkhoanguyen.vidstream.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.media.Ffmpeg;
import io.github.tanhoangkhoanguyen.vidstream.media.Ffprobe;
import io.github.tanhoangkhoanguyen.vidstream.media.TestMedia;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore;
import io.github.tanhoangkhoanguyen.vidstream.storage.GarageContainer;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import io.github.tanhoangkhoanguyen.vidstream.worker.WorkDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** A probed 40 s video in 10 s clips; the job cuts chunk 1 (clips 2 and 3). */
@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class ChunkIT {

  static final GarageContainer GARAGE = new GarageContainer();
  @TempDir static Path media;
  static TestMedia server;
  static ClipStore store;

  @TempDir Path work;
  @Autowired JdbcTemplate jdbc;
  ChunkJob chunk;
  ChunkExecutor executor;
  long dataset;
  long video;

  @BeforeAll
  static void start() throws Exception {
    GARAGE.start();
    store = new ClipStore(GARAGE.bootstrap("clips-d"));
    server = new TestMedia(media).ffmpeg("video.mp4", TestMedia.VIDEO_40S);
  }

  @AfterAll
  static void stop() {
    server.close();
    store.close();
    GARAGE.stop();
  }

  @BeforeEach
  void setUp() {
    var ffmpeg = new Ffmpeg(new Ffprobe());
    chunk = new ChunkJob(jdbc, (dataset, file) -> server.url(file), store);
    var source = mock(JobSource.class);
    when(source.uploads(any(), any()))
        .thenAnswer(call -> chunk.uploads(call.getArgument(0), call.getArgument(1)));
    executor = new ChunkExecutor(ffmpeg, source, new WorkDir(work));
    dataset =
        jdbc.queryForObject(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                + " fps, chunk_clips) VALUES ('d', '1', 10, 5, 30, 2) RETURNING id",
            Long.class);
    video =
        jdbc.queryForObject(
            "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes, duration_ms, status)"
                + " VALUES (?, 'video.mp4', 'aa', 1, 40000, 'PROBED') RETURNING id",
            Long.class,
            dataset);
    jdbc.update(
        "INSERT INTO clips (video_id, clip_index, start_ms, end_ms)"
            + " SELECT ?, i, i * 10000, (i + 1) * 10000 FROM generate_series(0, 3) i",
        video);
  }

  @Test
  void uploadsChunkAndCommitsItsKeys() throws Exception {
    var job =
        new Job(7, Type.CHUNK, dataset, video, "{\"chunk\": 1, \"first\": 2, \"last\": 3}", 4);

    var uploaded = executor.execute(job, chunk.prepare(job));
    assertThat(stored()).isEqualTo("0 - | 1 - | 2 - | 3 -"); // Nothing visible before the commit.
    chunk.commit(job, uploaded);

    assertThat(stored())
        .isEqualTo("0 - | 1 - | 2 %d/00002-7-4.mp4 | 3 %d/00003-7-4.mp4".formatted(video, video));
    var key = video + "/00002-7-4.mp4";
    var object = GARAGE.read("clips-d", key);
    assertThat(
            jdbc.queryForObject(
                "SELECT sha256 || ' ' || size_bytes FROM clips WHERE object_key = ?",
                String.class,
                key))
        .isEqualTo(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(object))
                + " "
                + object.length);
    assertThat(jdbc.queryForObject("SELECT status FROM videos", String.class)).isEqualTo("PROBED");
    try (var left = Files.list(work)) {
      assertThat(left).isEmpty(); // Local clips are gone once uploaded.
    }
  }

  @Test
  void uploadsOnlyTheChunksClips() {
    var job =
        new Job(7, Type.CHUNK, dataset, video, "{\"chunk\": 1, \"first\": 2, \"last\": 3}", 4);

    assertThatThrownBy(() -> chunk.uploads(job, List.of(new Blob(1, 3, "00".repeat(32)))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failedVideoTakesNoClips() throws Exception {
    var job =
        new Job(7, Type.CHUNK, dataset, video, "{\"chunk\": 0, \"first\": 0, \"last\": 0}", 1);
    var uploaded = executor.execute(job, chunk.prepare(job));
    // Another chunk of the video ran out of attempts meanwhile.
    jdbc.update("UPDATE videos SET status = 'FAILED'");

    chunk.commit(job, uploaded);

    assertThat(stored()).isEqualTo("0 - | 1 - | 2 - | 3 -"); // Its upload is left to the sweep.
    assertThat(video()).isEqualTo("FAILED 0");
  }

  @Test
  void lastChunkInAnyOrderClipsTheVideo() throws Exception {
    // Chunks {0}, {1}, {2, 3}, committed in the order 2, 0, 1.
    String[] payloads = {
      "\"first\": 2, \"last\": 3", "\"first\": 0, \"last\": 0", "\"first\": 1, \"last\": 1"
    };
    for (int i = 0; i < payloads.length; i++) {
      var job =
          new Job(
              10 + i,
              Type.CHUNK,
              dataset,
              video,
              "{\"chunk\": %d, %s}".formatted(i, payloads[i]),
              1);
      chunk.commit(job, executor.execute(job, chunk.prepare(job)));
      assertThat(video()).isEqualTo(i < 2 ? "PROBED 0" : "CLIPPED 4");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT string_agg(first_clip_index || ':' || start_ms || '-' || end_ms, ' '"
                    + " ORDER BY first_clip_index) FROM units",
                String.class))
        .isEqualTo("0:0-10000 1:10000-20000 2:20000-30000 3:30000-40000");
  }

  /** "status units". */
  String video() {
    return jdbc.queryForObject(
        "SELECT status || ' ' || (SELECT count(*) FROM units) FROM videos", String.class);
  }

  String stored() {
    return jdbc.queryForObject(
        "SELECT string_agg(clip_index || ' ' || coalesce(object_key, '-'), ' | '"
            + " ORDER BY clip_index) FROM clips",
        String.class);
  }
}
