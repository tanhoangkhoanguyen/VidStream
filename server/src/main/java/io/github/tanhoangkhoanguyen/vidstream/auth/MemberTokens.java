package io.github.tanhoangkhoanguyen.vidstream.auth;

import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** Box calls for a dataset with any member's token, so work goes on while users are offline. */
@Component
public class MemberTokens {

  private static final Logger log = LoggerFactory.getLogger(MemberTokens.class);

  private final JdbcClient jdbc;
  private final TokenVault vault;
  private final BoxClient box;

  MemberTokens(JdbcClient jdbc, TokenVault vault, BoxClient box) {
    this.jdbc = jdbc;
    this.vault = vault;
    this.box = box;
  }

  /**
   * Runs {@code call} with the first member token that works. Skips members without a usable token;
   * removes members who lost access to the dataset folder.
   *
   * @throws NoUsableMemberException no member could make the call
   */
  public <T> T call(long datasetId, Function<String, T> call) {
    var folder =
        jdbc.sql("SELECT box_folder_id FROM datasets WHERE id = ?")
            .param(datasetId)
            .query(String.class)
            .single();
    var members =
        jdbc.sql("SELECT user_id FROM dataset_members WHERE dataset_id = ? ORDER BY user_id")
            .param(datasetId)
            .query(Long.class)
            .list();
    for (long user : members) {
      var result = callAs(user, datasetId, folder, call);
      if (result.isPresent()) return result.get();
    }
    throw new NoUsableMemberException(datasetId);
  }

  /** Empty when this member cannot make the call. */
  private <T> Optional<T> callAs(
      long user, long datasetId, String folder, Function<String, T> call) {
    String token = null;
    try {
      token = vault.accessToken(user);
      try {
        return Optional.of(call.apply(token));
      } catch (HttpClientErrorException.Unauthorized e) {
        // Box ended the access token early: refresh once.
        vault.expire(user);
        token = vault.accessToken(user);
        try {
          return Optional.of(call.apply(token));
        } catch (HttpClientErrorException.Unauthorized again) {
          // Rejected right after a refresh: the grant is unusable; fail closed.
          log.warn("Box rejected a fresh token of user {}; tokens cleared", user);
          vault.clear(user);
          return Optional.empty();
        }
      }
    } catch (SignInRequiredException e) {
      return Optional.empty();
    } catch (HttpClientErrorException.NotFound e) {
      // Box answers 404 for "no access" too: the dataset folder tells the two apart.
      if (box.canAccess(token, folder)) throw e;
      log.warn("User {} lost access to dataset {}: membership removed", user, datasetId);
      jdbc.sql("DELETE FROM dataset_members WHERE user_id = ? AND dataset_id = ?")
          .params(user, datasetId)
          .update();
      return Optional.empty();
    }
  }
}
