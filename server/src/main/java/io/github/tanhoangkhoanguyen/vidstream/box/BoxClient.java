package io.github.tanhoangkhoanguyen.vidstream.box;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** The Box API calls VidStream makes. Read-only: GET only. */
@Component
public class BoxClient {

  private final RestClient http;

  public BoxClient(RestClient.Builder builder, BoxProperties props) {
    this.http = builder.baseUrl(props.apiUrl()).build();
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
}
