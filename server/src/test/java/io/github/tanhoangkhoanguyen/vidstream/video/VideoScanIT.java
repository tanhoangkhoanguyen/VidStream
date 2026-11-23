package io.github.tanhoangkhoanguyen.vidstream.video;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.auth.NoUsableMemberException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class VideoScanIT {

  @Autowired JdbcClient jdbc;
  final ConcurrentLinkedQueue<String> scanned = new ConcurrentLinkedQueue<>();

  @BeforeEach
  void setUp() {
    jdbc.sql(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                + " fps, chunk_clips, active) VALUES ('a', '1', 12, 5, 30, 50, true),"
                + " ('b', '2', 12, 5, 30, 50, true), ('old', '3', 12, 5, 30, 50, false)")
        .update();
  }

  static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("timed out").isLessThan(deadline);
      Thread.sleep(10);
    }
  }

  @Test
  void scansOneDatasetAtATime() throws Exception {
    var release = new CountDownLatch(1);
    var scan =
        new VideoScan(
            jdbc,
            (id, folder) -> {
              scanned.add(folder);
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    assertThat(scan.start("a")).isTrue();
    assertThat(scan.start("a")).isFalse();
    assertThat(scan.start("old")).isFalse();
    assertThat(scan.start("unknown")).isFalse();
    release.countDown();

    await(() -> scan.start("a"));
    await(() -> scanned.size() == 2);
    assertThat(scanned).containsOnly("1");
  }

  @Test
  void failedScanFreesTheDataset() throws Exception {
    var scan =
        new VideoScan(
            jdbc,
            (id, folder) -> {
              scanned.add(folder);
              throw new NoUsableMemberException(id);
            });

    assertThat(scan.start("a")).isTrue();
    await(() -> scan.start("a"));
  }

  @Test
  void scansEveryActiveDataset() throws Exception {
    var scan = new VideoScan(jdbc, (id, folder) -> scanned.add(folder));

    scan.scanActive();

    await(() -> scanned.size() == 2);
    assertThat(scanned).containsExactlyInAnyOrder("1", "2");
  }
}
