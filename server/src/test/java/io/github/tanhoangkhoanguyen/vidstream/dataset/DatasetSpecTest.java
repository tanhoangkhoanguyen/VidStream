package io.github.tanhoangkhoanguyen.vidstream.dataset;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DatasetSpecTest {

  static final String FOLDER = "335647443718";

  @Test
  void acceptsValidSpec() {
    assertThatNoException().isThrownBy(() -> new DatasetSpec("music-2026", FOLDER, 12, 5, 30, 50));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void rejects(String expected, ThrowingCallable create) {
    assertThatIllegalArgumentException().isThrownBy(create).withMessageContaining(expected);
  }

  static Stream<Arguments> rejects() {
    return Stream.of(
        bad("name", () -> new DatasetSpec(null, FOLDER, 12, 5, 30, 50)),
        bad("name", () -> new DatasetSpec("Music", FOLDER, 12, 5, 30, 50)),
        bad("name", () -> new DatasetSpec("-music", FOLDER, 12, 5, 30, 50)),
        bad("name", () -> new DatasetSpec("music_2026", FOLDER, 12, 5, 30, 50)),
        bad("name", () -> new DatasetSpec("a".repeat(64), FOLDER, 12, 5, 30, 50)),
        bad("box_folder_id", () -> new DatasetSpec("m", null, 12, 5, 30, 50)),
        bad("box_folder_id", () -> new DatasetSpec("m", "0", 12, 5, 30, 50)),
        bad("box_folder_id", () -> new DatasetSpec("m", "12a", 12, 5, 30, 50)),
        bad("clip_seconds", () -> new DatasetSpec("m", FOLDER, 4, 4, 30, 50)),
        bad("clip_seconds", () -> new DatasetSpec("m", FOLDER, 61, 5, 30, 50)),
        bad("min_last_clip_seconds", () -> new DatasetSpec("m", FOLDER, 12, 0, 30, 50)),
        bad("min_last_clip_seconds", () -> new DatasetSpec("m", FOLDER, 12, 13, 30, 50)),
        bad("fps", () -> new DatasetSpec("m", FOLDER, 12, 5, 29, 50)),
        bad("chunk_clips", () -> new DatasetSpec("m", FOLDER, 12, 5, 30, 0)));
  }

  static Arguments bad(String expected, ThrowingCallable create) {
    return arguments(expected, create);
  }
}
