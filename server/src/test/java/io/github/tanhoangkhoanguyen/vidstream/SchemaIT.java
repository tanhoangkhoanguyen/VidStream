package io.github.tanhoangkhoanguyen.vidstream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class SchemaIT {

  static final Map<String, Object> VALID =
      Map.of(
          "name", "music-2026",
          "box_folder_id", "335647443718",
          "clip_seconds", 12,
          "min_last_clip_seconds", 5,
          "fps", 30,
          "chunk_clips", 50);

  @Autowired JdbcClient jdbc;

  int insertDataset(Map<String, Object> overrides) {
    var row = new LinkedHashMap<>(VALID);
    row.putAll(overrides);
    var columns = String.join(", ", row.keySet());
    var params = row.keySet().stream().map(c -> ":" + c).collect(joining(", "));
    return jdbc.sql("INSERT INTO datasets (" + columns + ") VALUES (" + params + ")")
        .params(row)
        .update();
  }

  @Test
  void acceptsValidDataset() {
    assertThat(insertDataset(Map.of())).isEqualTo(1);
  }

  static Stream<Map<String, Object>> invalid() {
    return Stream.of(
        Map.of("clip_seconds", 4),
        Map.of("clip_seconds", 61),
        Map.of("min_last_clip_seconds", 0),
        Map.of("min_last_clip_seconds", 13),
        Map.of("fps", 29),
        Map.of("chunk_clips", 0),
        Map.of("name", "Music"),
        Map.of("name", "-music"),
        Map.of("name", "music_2026"));
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void rejectsInvalidDataset(Map<String, Object> override) {
    assertThatThrownBy(() -> insertDataset(override))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectsAccessTokenWithoutExpiry() {
    var sql =
        "INSERT INTO users (box_user_id, login, access_token_enc) VALUES ('1', 'a@usf.edu', '\\x01')";
    assertThatThrownBy(() -> jdbc.sql(sql).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
