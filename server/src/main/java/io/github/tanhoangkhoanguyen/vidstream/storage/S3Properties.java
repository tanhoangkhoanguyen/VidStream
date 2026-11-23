package io.github.tanhoangkhoanguyen.vidstream.storage;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The clip store: Garage, private to the deployment (docs/spikes/clip-storage.md).
 *
 * @param endpoint e.g. {@code http://s3.internal:3900}
 * @param publicEndpoint where workers upload, e.g. {@code https://s3.<domain>}; default: {@code
 *     endpoint}
 * @param capacity clip bytes all datasets may store together; clipping stops there. Default: half
 *     of Oracle Always Free's 200 GB of block storage.
 */
@ConfigurationProperties("vidstream.s3")
public record S3Properties(
    URI endpoint,
    URI publicEndpoint,
    @DefaultValue("garage") String region,
    String accessKey,
    String secretKey,
    @DefaultValue("100GB") DataSize capacity) {

  public S3Properties {
    if (publicEndpoint == null) publicEndpoint = endpoint;
  }
}
