package io.github.tanhoangkhoanguyen.vidstream.video;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.tanhoangkhoanguyen.vidstream.box.BoxPages.file;
import static io.github.tanhoangkhoanguyen.vidstream.box.BoxPages.folder;
import static io.github.tanhoangkhoanguyen.vidstream.box.BoxPages.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxProperties;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class VideoDiscoveryIT {

  @RegisterExtension
  static WireMockExtension box =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  VideoDiscovery discovery;
  long dataset;

  @BeforeEach
  void setUp() {
    var client = new BoxClient(RestClient.builder(), new BoxProperties(URI.create(box.baseUrl())));
    discovery =
        new VideoDiscovery(jdbc, txManager, client, new DiscoveryProperties(Set.of("mp4", "mpg")));
    dataset =
        jdbc.queryForObject(
            "INSERT INTO datasets (name, box_folder_id, clip_seconds, min_last_clip_seconds,"
                + " fps, chunk_clips) VALUES ('d', '1', 12, 5, 30, 50) RETURNING id",
            Long.class);
  }

  void stubItems(String folderId, int offset, String body) {
    box.stubFor(
        get(urlPathEqualTo("/folders/" + folderId + "/items"))
            .withQueryParam("offset", equalTo(String.valueOf(offset)))
            .willReturn(okJson(body)));
  }

  /** "box_file_id status" per video, then the box_file_id of each PROBE job's video. */
  String rows() {
    return jdbc.queryForObject(
        "SELECT string_agg(box_file_id || ' ' || status, ', ' ORDER BY box_file_id) || ' | '"
            + " || (SELECT string_agg(v.box_file_id, ', ' ORDER BY v.box_file_id)"
            + "     FROM jobs j JOIN videos v ON v.id = j.video_id WHERE j.type = 'PROBE')"
            + " FROM videos",
        String.class);
  }

  @Test
  void registersEachVideoOnceWithAProbe() {
    stubItems("1", 0, page(3, 0, folder("2"), file("10", "mp4", "aa")));
    stubItems("1", 2, page(3, 2, file("11", "lrf", "bb")));
    stubItems("2", 0, page(2, 0, file("12", "MP4", "aa"), file("13", "mpg", "cc")));
    var expected = "10 DISCOVERED, 12 DUPLICATE, 13 DISCOVERED | 10, 13";

    assertThat(discovery.discover(dataset, "1", "token")).isEqualTo(3);
    assertThat(rows()).isEqualTo(expected);

    assertThat(discovery.discover(dataset, "1", "token")).isZero();
    assertThat(rows()).isEqualTo(expected);
  }
}
