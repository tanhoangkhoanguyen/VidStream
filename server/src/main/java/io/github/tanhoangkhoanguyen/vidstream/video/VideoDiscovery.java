package io.github.tanhoangkhoanguyen.vidstream.video;

import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient.Item;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Registers the videos in a dataset's Box folder. Metadata only: nothing is downloaded. */
@Service
@Profile("web")
public class VideoDiscovery {

  private static final Logger log = LoggerFactory.getLogger(VideoDiscovery.class);

  // Known files are skipped. Content already registered under another file is a DUPLICATE:
  // never clipped or labeled.
  private static final String INSERT_VIDEO =
      """
      INSERT INTO videos (dataset_id, box_file_id, sha1, size_bytes, status)
      SELECT ?, ?, ?, ?, CASE WHEN EXISTS (
          SELECT 1 FROM videos WHERE dataset_id = ? AND sha1 = ? AND status <> 'DUPLICATE')
        THEN 'DUPLICATE' ELSE 'DISCOVERED' END
      ON CONFLICT DO NOTHING
      """;

  // One PROBE per discovered video; the jobs_once index skips videos that have one.
  private static final String ENQUEUE_PROBES =
      """
      INSERT INTO jobs (type, dataset_id, video_id)
      SELECT 'PROBE', dataset_id, id FROM videos WHERE dataset_id = ? AND status = 'DISCOVERED'
      ON CONFLICT DO NOTHING
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final BoxClient box;
  private final DiscoveryProperties props;

  VideoDiscovery(
      JdbcTemplate jdbc,
      PlatformTransactionManager txManager,
      BoxClient box,
      DiscoveryProperties props) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(txManager);
    this.box = box;
    this.props = props;
  }

  /**
   * Adds the folder's new videos, each with a PROBE job; a rescan adds nothing.
   *
   * @return how many videos were added
   */
  public int discover(long datasetId, String folderId, String accessToken) {
    var videos = box.filesUnder(accessToken, folderId).stream().filter(this::isVideo).toList();
    var rows =
        videos.stream()
            .map(v -> new Object[] {datasetId, v.id(), v.sha1(), v.size(), datasetId, v.sha1()})
            .toList();
    int added =
        tx.execute(status -> {
          int sum = Arrays.stream(jdbc.batchUpdate(INSERT_VIDEO, rows)).sum();
          jdbc.update(ENQUEUE_PROBES, datasetId);
          return sum;
        });
    log.info("Dataset {}: {} videos in Box, {} new", datasetId, videos.size(), added);
    return added;
  }

  private boolean isVideo(Item file) {
    return file.extension() != null
        && props.videoExtensions().contains(file.extension().toLowerCase(Locale.ROOT));
  }
}
