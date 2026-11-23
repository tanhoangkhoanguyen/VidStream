package io.github.tanhoangkhoanguyen.vidstream.video;

import io.github.tanhoangkhoanguyen.vidstream.auth.MemberTokens;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan;
import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan.Chunk;
import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan.Clip;
import io.github.tanhoangkhoanguyen.vidstream.dispatch.JobType;
import io.github.tanhoangkhoanguyen.vidstream.media.Ffprobe.Facts;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PROBE, server side: the video's download URL; then its clips and CHUNK jobs from its duration.
 */
@Component
@Profile("web")
class ProbeJob implements JobType<URI, Facts> {

  private static final Logger log = LoggerFactory.getLogger(ProbeJob.class);

  private record Settings(int clipSeconds, int minLastClipSeconds, int chunkClips) {}

  private final JdbcTemplate jdbc;
  private final DownloadUrls urls;

  @Autowired
  ProbeJob(JdbcTemplate jdbc, MemberTokens members, BoxClient box) {
    this(jdbc, DownloadUrls.box(members, box));
  }

  ProbeJob(JdbcTemplate jdbc, DownloadUrls urls) {
    this.jdbc = jdbc;
    this.urls = urls;
  }

  @Override
  public Type type() {
    return Type.PROBE;
  }

  /** The video's download URL. */
  @Override
  public URI prepare(Job job) {
    var file =
        jdbc.queryForObject(
            "SELECT box_file_id FROM videos WHERE id = ?", String.class, job.videoId());
    return urls.of(job.datasetId(), file);
  }

  /**
   * Only a DISCOVERED video is planned, so a rerun adds nothing. Each CHUNK names its clips: a
   * later change to the dataset's chunk_clips does not move them.
   */
  @Override
  public void commit(Job job, Facts facts) {
    if (!facts.hasVideo()) {
      noVideo(job.videoId());
      return;
    }
    var settings =
        jdbc.queryForObject(
            "SELECT clip_seconds, min_last_clip_seconds, chunk_clips FROM datasets WHERE id = ?",
            (rs, i) -> new Settings(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
            job.datasetId());
    var clips =
        ClipPlan.clips(facts.durationMs(), settings.clipSeconds(), settings.minLastClipSeconds());
    plan(job, facts.durationMs(), clips, ClipPlan.chunks(clips, settings.chunkClips()));
  }

  private void plan(Job job, long durationMs, List<Clip> clips, List<Chunk> chunks) {
    long video = job.videoId();
    if (jdbc.update(
            "UPDATE videos SET duration_ms = ?, status = 'PROBED'"
                + " WHERE id = ? AND status = 'DISCOVERED'",
            durationMs,
            video)
        == 0) return;
    jdbc.batchUpdate(
        "INSERT INTO clips (video_id, clip_index, start_ms, end_ms) VALUES (?, ?, ?, ?)",
        clips.stream().map(c -> new Object[] {video, c.index(), c.startMs(), c.endMs()}).toList());
    jdbc.batchUpdate(
        "INSERT INTO jobs (type, dataset_id, video_id, payload) VALUES ('CHUNK', ?, ?,"
            + " jsonb_build_object('chunk', ?::int, 'first', ?::int, 'last', ?::int))",
        chunks.stream()
            .map(c -> new Object[] {job.datasetId(), video, c.index(), c.firstClip(), c.lastClip()})
            .toList());
    log.info("Video {}: {} clips in {} chunks", video, clips.size(), chunks.size());
  }

  private void noVideo(long video) {
    jdbc.update(
        "UPDATE videos SET status = 'FAILED', last_error = 'no video stream'"
            + " WHERE id = ? AND status = 'DISCOVERED'",
        video);
    log.warn("Video {}: no video stream", video);
  }
}
