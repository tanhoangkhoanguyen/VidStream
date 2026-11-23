package io.github.tanhoangkhoanguyen.vidstream.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkDirTest {

  @TempDir Path root;

  Path dir(String name, Instant modified) throws Exception {
    var dir = Files.createDirectories(root.resolve(name));
    Files.writeString(dir.resolve("00000.mp4"), "clip");
    Files.setLastModifiedTime(dir, FileTime.from(modified));
    return dir;
  }

  @Test
  void sweepDeletesOnlyStaleAttempts() throws Exception {
    var stale = Instant.now().minus(WorkDir.STALE).minusSeconds(60);
    var running = dir("7-1", Instant.now());
    var crashed = dir("8-1", stale);
    var other = dir("notes", stale);

    new WorkDir(root).sweep();

    assertThat(running).exists();
    assertThat(other).exists();
    assertThat(crashed).doesNotExist();
  }
}
