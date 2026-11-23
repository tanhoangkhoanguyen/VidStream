package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param workers hex SHA-256 of each worker token, by worker name ({@link WorkerTokens})
 */
@ConfigurationProperties("vidstream.dispatch")
public record DispatchProperties(Map<String, String> workers) {

  public DispatchProperties {
    workers = workers == null ? Map.of() : Map.copyOf(workers);
  }
}
