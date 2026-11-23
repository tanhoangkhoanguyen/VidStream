package io.github.tanhoangkhoanguyen.vidstream.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class ClipStoreIT {

  static final GarageContainer GARAGE = new GarageContainer();
  static ClipStore store;

  @BeforeAll
  static void start() throws Exception {
    GARAGE.start();
    store = new ClipStore(GARAGE.bootstrap("clips-a"));
  }

  @AfterAll
  static void stop() {
    store.close();
    GARAGE.stop();
  }

  static byte[] read(String key) {
    return GARAGE.read("clips-a", key);
  }

  @Test
  void delete() {
    GARAGE.write("clips-a", "7/00000-1-1.mp4", new byte[] {1, 2, 3});

    store.delete("clips-a", "7/00000-1-1.mp4");
    assertThatThrownBy(() -> read("7/00000-1-1.mp4")).isInstanceOf(NoSuchKeyException.class);
  }

  @Test
  void ensureBucketCreatesItOnceForThisKey() throws Exception {
    store.ensureBucket("clips-new");
    store.ensureBucket("clips-new"); // A restart.
    store.ensureBucket("clips-a"); // Created by the operator.

    byte[] clip = {1};
    var upload = store.presignPut("clips-new", "1/00000-1-1.mp4", clip.length, sha256(clip));
    assertThat(send(upload, clip)).isEqualTo(200);
    assertThat(store.keysOlderThan("clips-new", "1/", Instant.now().plusSeconds(1)))
        .containsExactly("1/00000-1-1.mp4");
  }

  /** The store enforces what was signed (docs/spikes/clip-storage.md). */
  @Test
  void presignedPutTakesOnlyTheSignedBytes() throws Exception {
    byte[] clip = {1, 2, 3};
    var upload = store.presignPut("clips-a", "7/00001-1-1.mp4", clip.length, sha256(clip));

    assertThat(send(upload, new byte[] {1, 2, 4})).as("other bytes").isEqualTo(400);
    assertThat(send(upload, new byte[] {1, 2, 3, 4})).as("other length").isEqualTo(403);
    assertThatThrownBy(() -> read("7/00001-1-1.mp4")).isInstanceOf(NoSuchKeyException.class);

    assertThat(send(upload, clip)).isEqualTo(200);
    assertThat(read("7/00001-1-1.mp4")).containsExactly(1, 2, 3);
  }

  static int send(ClipStore.Upload upload, byte[] body) throws Exception {
    var request = HttpRequest.newBuilder(upload.url()).PUT(BodyPublishers.ofByteArray(body));
    upload.headers().forEach(request::header);
    try (var http = HttpClient.newHttpClient()) {
      return http.send(request.build(), BodyHandlers.discarding()).statusCode();
    }
  }

  static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
