package io.github.tanhoangkhoanguyen.vidstream.video;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.media.Ffprobe;
import io.github.tanhoangkhoanguyen.vidstream.media.TestMedia;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class ProbeIT {

  @TempDir static Path dir;
  static TestMedia media;

  @Autowired JdbcTemplate jdbc;
  ProbeJob probe;
  ProbeExecutor executor;
  long dataset;

  @BeforeAll
  static void serveMedia() throws Exception {
    media =
        new TestMedia(dir)
            .ffmpeg("video.mp4", TestMedia.VIDEO_40S)
            .ffmpeg("audio.m4a", TestMedia.AUDIO_5S);
  }

  @AfterAll
  static void stop() {
    media.close();
  }

  @BeforeEach
  void setUp() {
    // Box file IDs are the served file names here.
    probe = new ProbeJob(jdbc, (dataset, file) -> media.url(file));
    executor = new ProbeExecutor(new Ffprobe());
    dataset =
        jdbc.queryForObject(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                + " fps, chunk_clips) VALUES ('d', '1', 10, 5, 30, 50) RETURNING id",
            Long.class);
  }

  Job probe(String file) throws Exception {
    long video =
        jdbc.queryForObject(
            "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes)"
                + " VALUES (?, ?, 'aa', 1) RETURNING id",
            Long.class,
            dataset,
            file);
    var job = new Job(1, Type.PROBE, dataset, video, "{}", 1);
    probe.commit(job, executor.execute(job, probe.prepare(job)));
    return job;
  }

  String video(Job job) {
    return jdbc.queryForObject(
        "SELECT concat_ws(' ', status, last_error) FROM videos WHERE id = ?",
        String.class,
        job.videoId());
  }

  String plan() {
    return jdbc.queryForObject(
        "SELECT (SELECT string_agg(clip_index || ':' || start_ms, ' ' ORDER BY clip_index)"
            + " FROM clips) || ' | ' || (SELECT string_agg(payload::text, ' ') FROM jobs)",
        String.class);
  }

  @Test
  void plansClipsAndChunks() throws Exception {
    var job = probe("video.mp4");

    assertThat(video(job)).isEqualTo("PROBED");
    assertThat(plan())
        .isEqualTo("0:0 1:10000 2:20000 3:30000 | {\"last\": 3, \"chunk\": 0, \"first\": 0}");
    assertThat(
            jdbc.queryForObject(
                "SELECT end_ms = (SELECT duration_ms FROM videos) FROM clips WHERE clip_index = 3",
                Boolean.class))
        .isTrue();
  }

  @Test
  void rerunAddsNothing() throws Exception {
    var job = probe("video.mp4");
    var planned = plan();

    probe.commit(job, executor.execute(job, probe.prepare(job)));

    assertThat(plan()).isEqualTo(planned);
  }

  @Test
  void noVideoStreamFailsTheVideo() throws Exception {
    var job = probe("audio.m4a");

    assertThat(video(job)).isEqualTo("FAILED no video stream");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM clips", Long.class)).isZero();
  }
}
