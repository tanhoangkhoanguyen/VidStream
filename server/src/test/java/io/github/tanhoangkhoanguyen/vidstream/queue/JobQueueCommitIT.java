package io.github.tanhoangkhoanguyen.vidstream.queue;

import static io.github.tanhoangkhoanguyen.vidstream.queue.JobQueue.Type.CHUNK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Not rolled back: commit and rollback are what is tested. Cleaned up after each test.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobQueueCommitIT extends JobQueueSupport {

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM jobs WHERE dataset_id = ?").param(dataset).update();
    jdbc.sql("DELETE FROM videos WHERE dataset_id = ?").param(dataset).update();
    jdbc.sql("DELETE FROM datasets WHERE id = ?").param(dataset).update();
  }

  int jobCount() {
    return jdbc.sql("SELECT count(*) FROM jobs").query(Integer.class).single();
  }

  @Test
  void completeCommitsWorkWithTheJob() {
    var id = enqueue(CHUNK, 0);

    queue.complete(claim("w1").orElseThrow(), () -> enqueue(CHUNK, 1));

    assertThat(state(id)).isEqualTo("DONE w1 1");
    assertThat(jobCount()).isEqualTo(2);
  }

  @Test
  void failedWorkLeavesJobLeased() {
    var id = enqueue(CHUNK, 0);
    var job = claim("w1").orElseThrow();

    assertThatThrownBy(() ->
            queue.complete(
                job,
                () -> {
                  enqueue(CHUNK, 1);
                  throw new IllegalStateException("ffprobe failed");
                }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(state(id)).isEqualTo("LEASED w1 1");
    assertThat(jobCount()).isEqualTo(1);
  }

  @Test
  void staleWorkerCommitsNothing() {
    var id = enqueue(CHUNK, 0);
    var stale = claim("w1").orElseThrow();
    update("lease_until = now() - interval '1 second'");
    var current = claim("w2").orElseThrow();

    assertThatThrownBy(() -> queue.complete(stale, () -> enqueue(CHUNK, 1)))
        .isInstanceOf(StaleLeaseException.class);
    assertThat(jobCount()).isEqualTo(1);

    queue.complete(current, () -> {});
    assertThat(state(id)).isEqualTo("DONE w2 2");
  }
}
