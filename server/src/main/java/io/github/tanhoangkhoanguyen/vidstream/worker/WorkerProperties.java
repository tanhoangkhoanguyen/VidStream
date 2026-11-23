package io.github.tanhoangkhoanguyen.vidstream.worker;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param slots jobs run at once on this node
 * @param workDir local scratch for clips until they are uploaded; default: the JVM temp dir
 * @param minFree below this free space in the work dir, jobs that write there are not claimed
 * @param server the web node workers claim from, e.g. {@code https://<domain>}
 * @param token this machine's worker token, issued by the operator ({@code WorkerTokens}); a
 *     secret: set it by environment ({@code VIDSTREAM_WORKER_TOKEN})
 */
@ConfigurationProperties("vidstream.worker")
public record WorkerProperties(
    @DefaultValue("1") int slots,
    Path workDir,
    @DefaultValue("2GB") DataSize minFree,
    URI server,
    String token) {

  public WorkerProperties {
    if (slots < 1) throw new IllegalArgumentException("vidstream.worker.slots must be >= 1");
    if (workDir == null) workDir = Path.of(System.getProperty("java.io.tmpdir"), "vidstream");
    if (minFree == null) minFree = DataSize.ofGigabytes(2);
  }
}
