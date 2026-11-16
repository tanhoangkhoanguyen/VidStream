package io.github.tanhoangkhoanguyen.vidstream.dataset;

import static java.util.stream.Collectors.toUnmodifiableSet;

import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Which active datasets a Box user can read: one Box call per dataset, run in parallel. */
@Component
@Profile("web")
public class DatasetAccess {

  private final JdbcClient jdbc;
  private final BoxClient box;

  public DatasetAccess(JdbcClient jdbc, BoxClient box) {
    this.jdbc = jdbc;
    this.box = box;
  }

  /** Names of the readable datasets. A Box error is thrown, never read as "no access". */
  public Set<String> readableBy(String accessToken) {
    var folders =
        jdbc.sql("SELECT name, box_folder_id FROM datasets WHERE active")
            .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
            .list();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var checks =
          folders.stream()
              .map(f ->
                  CompletableFuture.supplyAsync(
                      () -> box.canAccess(accessToken, f.getValue()) ? f.getKey() : null, pool))
              .toList();
      return checks.stream()
          .map(CompletableFuture::join)
          .filter(Objects::nonNull)
          .collect(toUnmodifiableSet());
    } catch (CompletionException e) {
      throw e.getCause() instanceof RuntimeException cause ? cause : e;
    }
  }
}
