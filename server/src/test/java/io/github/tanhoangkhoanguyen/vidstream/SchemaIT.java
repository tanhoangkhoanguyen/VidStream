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

  long insertDataset(Map<String, Object> overrides) {
    var row = new LinkedHashMap<>(VALID);
    row.putAll(overrides);
    var columns = String.join(", ", row.keySet());
    var params = row.keySet().stream().map(c -> ":" + c).collect(joining(", "));
    return jdbc.sql("INSERT INTO datasets (" + columns + ") VALUES (" + params + ") RETURNING id")
        .params(row)
        .query(Long.class)
        .single();
  }

  long insertVideo(long dataset, String fileId, String sha1, String status) {
    return jdbc.sql(
            "INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes, status)"
                + " VALUES (?, ?, ?, 1, ?) RETURNING id")
        .params(dataset, fileId, sha1, status)
        .query(Long.class)
        .single();
  }

  void insertJob(long video, String type, String payload) {
    jdbc.sql(
            "INSERT INTO jobs (type, dataset_id, video_id, payload)"
                + " SELECT ?, dataset_id, id, ?::jsonb FROM videos WHERE id = ?")
        .params(type, payload, video)
        .update();
  }

  // Postgres aborts the transaction on error, so a rejected insert must come last.
  static void assertRejected(Runnable insert) {
    assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void acceptsValidDataset() {
    assertThat(insertDataset(Map.of())).isPositive();
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
    assertRejected(() -> insertDataset(override));
  }

  @Test
  void rejectsAccessTokenWithoutExpiry() {
    var sql =
        "INSERT INTO users (box_user_id, login, access_token_enc) VALUES ('1', 'a@usf.edu', '\\x01')";
    assertRejected(() -> jdbc.sql(sql).update());
  }

  @Test
  void rejectsSameFileTwice() {
    var dataset = insertDataset(Map.of());
    insertVideo(dataset, "1", "aa", "DISCOVERED");
    assertRejected(() -> insertVideo(dataset, "1", "bb", "DISCOVERED"));
  }

  @Test
  void rejectsSameContentUnlessDuplicate() {
    var dataset = insertDataset(Map.of());
    insertVideo(dataset, "1", "aa", "DISCOVERED");
    insertVideo(dataset, "2", "aa", "DUPLICATE");
    assertRejected(() -> insertVideo(dataset, "3", "aa", "DISCOVERED"));
  }

  @Test
  void acceptsOneProbeAndEachChunkOnce() {
    var video = insertVideo(insertDataset(Map.of()), "1", "aa", "DISCOVERED");
    insertJob(video, "PROBE", "{}");
    insertJob(video, "CHUNK", "{\"chunk\": 0}");
    insertJob(video, "CHUNK", "{\"chunk\": 1}");
    assertRejected(() -> insertJob(video, "PROBE", "{}"));
  }

  @Test
  void rejectsSameChunkTwice() {
    var video = insertVideo(insertDataset(Map.of()), "1", "aa", "DISCOVERED");
    insertJob(video, "CHUNK", "{\"chunk\": 0}");
    assertRejected(() -> insertJob(video, "CHUNK", "{\"chunk\": 0}"));
  }

  @Test
  void rejectsLeaseWithoutExpiry() {
    var video = insertVideo(insertDataset(Map.of()), "1", "aa", "DISCOVERED");
    insertJob(video, "PROBE", "{}");
    assertRejected(() -> jdbc.sql("UPDATE jobs SET status = 'LEASED'").update());
  }
}
