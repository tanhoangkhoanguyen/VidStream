package io.github.tanhoangkhoanguyen.vidstream.storage;

import static java.util.stream.Collectors.toMap;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Clip objects: one bucket per dataset; an object is written once and never overwritten. */
@Component
@Profile("web")
public class ClipStore implements AutoCloseable {

  /** Long enough to start a chunk's uploads; S3 checks it when a request starts. */
  static final Duration UPLOAD_EXPIRY = Duration.ofMinutes(15);

  /** A presigned {@code PUT}: send {@code headers} with exactly the signed bytes. */
  public record Upload(URI url, Map<String, String> headers) {}

  private static final Set<String> SET_BY_CLIENT = Set.of("host", "content-length");

  private final S3Client s3;
  private final S3Presigner presigner;

  public ClipStore(S3Properties props) {
    this.s3 = client(props);
    this.presigner =
        S3Presigner.builder()
            // The signature covers the host: sign with the one workers reach.
            .endpointOverride(props.publicEndpoint())
            .region(Region.of(props.region()))
            .credentialsProvider(credentials(props))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
  }

  static S3Client client(S3Properties props) {
    return S3Client.builder()
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .endpointOverride(props.endpoint())
        .region(Region.of(props.region()))
        .credentialsProvider(credentials(props))
        // Garage serves buckets by path, not by subdomain.
        .forcePathStyle(true)
        // Checksums only where S3 requires them: not every S3 store accepts the new defaults.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
  }

  private static StaticCredentialsProvider credentials(S3Properties props) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
  }

  /**
   * Lets the holder write one object, once its bytes are known: the store rejects any other length
   * or SHA-256 (docs/spikes/clip-storage.md). No credentials leave the server.
   *
   * @param sha256 hex
   */
  public Upload presignPut(String bucket, String key, long size, String sha256) {
    var checksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256));
    var signed =
        presigner.presignPutObject(r ->
            r.signatureDuration(UPLOAD_EXPIRY)
                .putObjectRequest(p ->
                    p.bucket(bucket)
                        .key(key)
                        .contentType("video/mp4")
                        .contentLength(size)
                        .checksumSHA256(checksum)));
    // The HTTP client sets these two itself.
    var headers =
        signed.signedHeaders().entrySet().stream()
            .filter(h -> !SET_BY_CLIENT.contains(h.getKey().toLowerCase(Locale.ROOT)))
            .collect(toMap(Map.Entry::getKey, h -> String.join(",", h.getValue())));
    try {
      return new Upload(signed.url().toURI(), headers);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e); // The SDK builds a valid URL.
    }
  }

  /** One bucket per dataset: Garage grants keys per bucket, not per prefix. */
  public static String bucket(String dataset) {
    return "clips-" + dataset;
  }

  /** Creates the bucket unless it exists; this key then reads and writes it. */
  public void ensureBucket(String bucket) {
    try {
      s3.createBucket(b -> b.bucket(bucket));
    } catch (BucketAlreadyOwnedByYouException e) {
      // Created at an earlier start.
    }
  }

  /** The bucket's top-level prefixes, e.g. {@code 7/}: read page by page as iterated. */
  public Iterable<String> prefixes(String bucket) {
    return () ->
        s3.listObjectsV2Paginator(b -> b.bucket(bucket).delimiter("/")).commonPrefixes().stream()
            .map(CommonPrefix::prefix)
            .iterator();
  }

  /** Keys under {@code prefix} of objects written before {@code cutoff}. */
  public List<String> keysOlderThan(String bucket, String prefix, Instant cutoff) {
    return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
        .filter(o -> o.lastModified().isBefore(cutoff))
        .map(S3Object::key)
        .toList();
  }

  public void delete(String bucket, String key) {
    s3.deleteObject(b -> b.bucket(bucket).key(key));
  }

  @Override
  public void close() {
    presigner.close();
    s3.close();
  }
}
