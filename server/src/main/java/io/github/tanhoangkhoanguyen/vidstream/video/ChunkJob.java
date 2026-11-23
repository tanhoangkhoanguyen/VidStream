package io.github.tanhoangkhoanguyen.vidstream.video;

import io.github.tanhoangkhoanguyen.vidstream.auth.MemberTokens;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan.Clip;
import io.github.tanhoangkhoanguyen.vidstream.dispatch.JobType;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import io.github.tanhoangkhoanguyen.vidstream.video.ChunkExecutor.Work;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * CHUNK, server side: the clips to cut; uploads under keys unique to the attempt; then the clips,
 * committed with the job. A stale attempt's commit is rejected, so its objects are never referenced
 * (orphans, swept later).
 */
@Component
@Profile("web")
class ChunkJob implements JobType<Work, List<Blob>> {

  private record Payload(int chunk, int first, int last) {}

  private record Target(String boxFileId, int fps) {}

  private static final JsonMapper JSON = new JsonMapper();

  private final JdbcTemplate jdbc;
  private final DownloadUrls urls;
  private final ClipStore store;

  @Autowired
  ChunkJob(JdbcTemplate jdbc, MemberTokens members, BoxClient box, ClipStore store) {
    this(jdbc, DownloadUrls.box(members, box), store);
  }

  ChunkJob(JdbcTemplate jdbc, DownloadUrls urls, ClipStore store) {
    this.jdbc = jdbc;
    this.urls = urls;
    this.store = store;
  }

  @Override
  public Type type() {
    return Type.CHUNK;
  }

  @Override
  public Work prepare(Job job) {
    var payload = JSON.readValue(job.payload(), Payload.class);
    var target =
        jdbc.queryForObject(
            "SELECT v.box_file_id, d.fps FROM videos v JOIN datasets d"
                + " ON d.id = v.dataset_id WHERE v.id = ?",
            (rs, i) -> new Target(rs.getString(1), rs.getInt(2)),
            job.videoId());
    var clips =
        jdbc.query(
            "SELECT clip_index, start_ms, end_ms FROM clips"
                + " WHERE video_id = ? AND clip_index BETWEEN ? AND ? ORDER BY clip_index",
            (rs, i) -> new Clip(rs.getInt(1), rs.getLong(2), rs.getLong(3)),
            job.videoId(),
            payload.first(),
            payload.last());
    return new Work(urls.of(job.datasetId(), target.boxFileId()), target.fps(), clips);
  }

  /** Only the chunk's clips, each under its key for this attempt. */
  @Override
  public List<Upload> uploads(Job job, List<Blob> clips) {
    var payload = JSON.readValue(job.payload(), Payload.class);
    for (var c : clips)
      if (c.index() < payload.first() || c.index() > payload.last())
        throw new IllegalArgumentException("clip " + c.index() + " is not in the chunk");
    var bucket =
        ClipStore.bucket(
            jdbc.queryForObject(
                "SELECT name FROM datasets WHERE id = ?", String.class, job.datasetId()));
    return clips.stream()
        .map(c -> store.presignPut(bucket, key(job, c.index()), c.size(), c.sha256()))
        .toList();
  }

  /** Unique to the attempt: a stale one never overwrites the committed object. */
  static String key(Job job, int clip) {
    return "%d/%05d-%d-%d.mp4".formatted(job.videoId(), clip, job.id(), job.leaseVersion());
  }

  /**
   * Runs with the job's completion: the clips become visible only now. The chunk that completes the
   * video, in any order, makes one unit per clip and sets it CLIPPED. The video row lock makes
   * chunks committing at once take turns, so exactly one of them sees every clip stored. A failed
   * video takes no clips: they would hold quota and never be labeled; the sweep deletes them.
   */
  @Override
  public void commit(Job job, List<Blob> stored) {
    long video = job.videoId();
    var status =
        jdbc.queryForObject(
            "SELECT status FROM videos WHERE id = ? FOR UPDATE", String.class, video);
    if (status.equals("FAILED")) return;
    jdbc.batchUpdate(
        "UPDATE clips SET object_key = ?, sha256 = ?, size_bytes = ?"
            + " WHERE video_id = ? AND clip_index = ? AND object_key IS NULL",
        stored.stream()
            .map(c -> new Object[] {key(job, c.index()), c.sha256(), c.size(), video, c.index()})
            .toList());
    boolean done =
        jdbc.queryForObject(
            "SELECT NOT EXISTS (SELECT 1 FROM clips WHERE video_id = ? AND object_key IS NULL)",
            Boolean.class,
            video);
    if (!done) return;
    jdbc.update(
        "INSERT INTO units (video_id, first_clip_index, last_clip_index, start_ms, end_ms,"
            + " object_key) SELECT video_id, clip_index, clip_index, start_ms, end_ms, object_key"
            + " FROM clips WHERE video_id = ?",
        video);
    jdbc.update("UPDATE videos SET status = 'CLIPPED' WHERE id = ?", video);
  }
}
