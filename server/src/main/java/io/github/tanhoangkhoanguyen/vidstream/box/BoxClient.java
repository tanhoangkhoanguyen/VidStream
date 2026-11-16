package io.github.tanhoangkhoanguyen.vidstream.box;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** The Box API calls VidStream makes. Read-only: GET only. */
@Component
public class BoxClient {

  /** Box allows up to 1000 items per page. */
  static final int PAGE_SIZE = 1000;

  /** A folder entry, without its name. {@code extension} and {@code sha1}: files only. */
  public record Item(String type, String id, long size, String extension, String sha1) {}

  private record Page(List<Item> entries, @JsonProperty("total_count") long totalCount) {}

  private final RestClient http;

  @Autowired
  public BoxClient(RestClient.Builder builder, BoxProperties props) {
    this(builder, props, Thread::sleep);
  }

  BoxClient(RestClient.Builder builder, BoxProperties props, BoxRetry.Sleeper sleeper) {
    this.http = builder.baseUrl(props.apiUrl()).requestInterceptor(new BoxRetry(sleeper)).build();
  }

  /** Whether the token's user can read the folder. Box answers 404 for both no access and none. */
  public boolean canAccess(String accessToken, String folderId) {
    try {
      http.get()
          .uri("/folders/{id}?fields=id", folderId)
          .headers(h -> h.setBearerAuth(accessToken))
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.NotFound e) {
      return false;
    }
  }

  /** All direct entries of a folder, one call per {@link #PAGE_SIZE} entries. */
  public List<Item> folderItems(String accessToken, String folderId) {
    var items = new ArrayList<Item>();
    while (true) {
      var page =
          http.get()
              .uri(
                  "/folders/{id}/items?fields=type,id,size,extension,sha1&limit={limit}"
                      + "&offset={offset}",
                  folderId,
                  PAGE_SIZE,
                  items.size())
              .headers(h -> h.setBearerAuth(accessToken))
              .retrieve()
              .body(Page.class);
      items.addAll(page.entries());
      if (page.entries().isEmpty() || items.size() >= page.totalCount()) return items;
    }
  }
}
