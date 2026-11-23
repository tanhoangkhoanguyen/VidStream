package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Job;
import io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type;
import io.github.tanhoangkhoanguyen.vidstream.storage.ClipStore.Upload;
import io.github.tanhoangkhoanguyen.vidstream.worker.JobSource.Blob;
import java.util.List;

/**
 * A job type's server side, with the database and Box tokens: what a worker's {@code JobExecutor}
 * needs, and what to do with what it made.
 *
 * @param <W> the work handed to the worker
 * @param <R> the result it sends back
 */
public interface JobType<W, R> {

  Type type();

  /** At claim time. */
  W prepare(Job job);

  /** While it runs, for the files it made. */
  default List<Upload> uploads(Job job, List<Blob> blobs) {
    throw new IllegalArgumentException(type() + " uploads nothing");
  }

  /** With the job's completion, only if its lease is still held. */
  void commit(Job job, R result);
}
