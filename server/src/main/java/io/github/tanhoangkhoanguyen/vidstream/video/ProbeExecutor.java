package io.github.tanhoangkhoanguyen.vidstream.video;

import io.github.tanhoangkhoanguyen.vidstream.media.Ffprobe;
import io.github.tanhoangkhoanguyen.vidstream.media.Ffprobe.Facts;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobExecutor;
import java.net.URI;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** PROBE, on the worker: reads the video's duration from its download URL. */
@Component
@Profile("worker")
class ProbeExecutor implements JobExecutor<URI, Facts> {

  private final Ffprobe ffprobe;

  ProbeExecutor(Ffprobe ffprobe) {
    this.ffprobe = ffprobe;
  }

  @Override
  public Type type() {
    return Type.PROBE;
  }

  @Override
  public Facts execute(Job job, URI url) throws Exception {
    return ffprobe.probe(url);
  }
}
