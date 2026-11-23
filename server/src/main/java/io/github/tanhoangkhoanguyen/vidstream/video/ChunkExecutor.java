package io.github.tanhoangkhoanguyen.vidstream.video;

import io.github.tanhoangkhoanguyen.vidstream.clip.ClipPlan.Clip;
import io.github.tanhoangkhoanguyen.vidstream.media.Ffmpeg;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobExecutor;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import io.github.tanhoangkhoanguyen.vidstream.worker.WorkDir;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** CHUNK, on the worker: cuts the chunk's clips from Box and uploads each where the server says. */
@Component
@Profile("worker")
class ChunkExecutor implements JobExecutor<ChunkExecutor.Work, List<Blob>> {

  record Work(URI url, int fps, List<Clip> clips) {}

  private final Ffmpeg ffmpeg;
  private final JobSource source;
  private final WorkDir workDir;
  private final HttpClient http = HttpClient.newHttpClient();

  ChunkExecutor(Ffmpeg ffmpeg, JobSource source, WorkDir workDir) {
    this.ffmpeg = ffmpeg;
    this.source = source;
    this.workDir = workDir;
  }

  @Override
  public Type type() {
    return Type.CHUNK;
  }

  @Override
  public boolean usesDisk() {
    return true;
  }

  @Override
  public List<Blob> execute(Job job, Work work) throws Exception {
    var dir = workDir.create(job);
    try {
      ffmpeg.clipChunk(work.url(), work.clips(), work.fps(), dir);
      var files = new ArrayList<Path>();
      var blobs = new ArrayList<Blob>();
      for (var clip : work.clips()) {
        var file = dir.resolve(Ffmpeg.CLIP_NAME.formatted(clip.index()));
        files.add(file);
        blobs.add(new Blob(clip.index(), Files.size(file), sha256(file)));
      }
      var uploads = source.uploads(job, blobs);
      for (int i = 0; i < files.size(); i++) put(uploads.get(i), files.get(i));
      return blobs;
    } finally {
      WorkDir.delete(dir);
    }
  }

  private void put(Upload upload, Path file) throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(upload.url()).PUT(BodyPublishers.ofFile(file));
    upload.headers().forEach(request::header);
    int status = http.send(request.build(), BodyHandlers.discarding()).statusCode();
    // Not the URL: it is signed.
    if (status != 200) throw new IOException("clip upload rejected: HTTP " + status);
  }

  private static String sha256(Path file) throws IOException {
    var digest = digest();
    try (var in = Files.newInputStream(file)) {
      var buffer = new byte[64 * 1024];
      for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // Every JDK has SHA-256.
    }
  }
}
