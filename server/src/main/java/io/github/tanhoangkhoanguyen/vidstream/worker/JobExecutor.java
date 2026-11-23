package io.github.tanhoangkhoanguyen.vidstream.worker;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;

/**
 * A job type's slow step (download, ffmpeg, upload), on a worker without the database (#153). Its
 * server side prepares the work and commits the result (dispatch's {@code JobType}).
 *
 * @param <W> what it needs, e.g. a download URL and the clips to cut
 * @param <R> what it found or made, e.g. the uploaded clips' keys
 */
public interface JobExecutor<W, R> {

  Type type();

  /** Writes to the work dir: not claimed while the disk is low. */
  default boolean usesDisk() {
    return false;
  }

  R execute(Job job, W work) throws Exception;
}
