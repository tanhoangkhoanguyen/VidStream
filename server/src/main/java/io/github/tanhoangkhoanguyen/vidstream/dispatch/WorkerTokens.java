package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Worker tokens, issued by the operator: one per machine or group of machines, e.g. {@code gcp}.
 * Config holds only each token's SHA-256, so a leak of it gives no usable token:
 *
 * <pre>
 * token=$(openssl rand -hex 32)          # the worker's VIDSTREAM_WORKER_TOKEN
 * printf %s "$token" | sha256sum         # the web's VIDSTREAM_DISPATCH_WORKERS_GCP
 * </pre>
 *
 * Revoking one is removing its line and restarting the web node.
 */
@Component
@Profile("web")
public class WorkerTokens {

  private static final Logger log = LoggerFactory.getLogger(WorkerTokens.class);

  /** Becomes the worker ID's prefix: {@code <name>:<slot>}. */
  private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,32}");

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  /** Name by token hash. */
  private final Map<String, String> names = new HashMap<>();

  @Autowired
  WorkerTokens(DispatchProperties props) {
    this(props.workers());
  }

  /**
   * @param hashes hex SHA-256 of each token, by worker name
   */
  public WorkerTokens(Map<String, String> hashes) {
    hashes.forEach((name, hash) -> {
      if (!NAME.matcher(name).matches())
        throw new IllegalArgumentException("worker name must match " + NAME + ": " + name);
      var hex = hash.strip().toLowerCase(Locale.ROOT);
      if (!SHA256.matcher(hex).matches())
        throw new IllegalArgumentException("worker " + name + ": not a hex SHA-256");
      if (names.put(hex, name) != null)
        throw new IllegalArgumentException("worker " + name + ": token shared with another");
    });
    if (names.isEmpty()) log.warn("No worker tokens: set vidstream.dispatch.workers.<name>");
  }

  /** The worker's name, if the token is one of the configured ones. */
  public Optional<String> authenticate(String token) {
    return Optional.ofNullable(names.get(sha256(token)));
  }

  static String sha256(String token) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // Every JDK has SHA-256.
    }
  }
}
