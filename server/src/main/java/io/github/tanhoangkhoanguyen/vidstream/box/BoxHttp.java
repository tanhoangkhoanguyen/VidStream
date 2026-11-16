package io.github.tanhoangkhoanguyen.vidstream.box;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * HTTP for every Box call. Timeouts, so a hung call never holds a scan or a token row lock; no
 * redirects, so a download URL is read, never fetched.
 */
public final class BoxHttp {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

  private BoxHttp() {}

  public static ClientHttpRequestFactory requestFactory() {
    return requestFactory(CONNECT_TIMEOUT, READ_TIMEOUT);
  }

  static ClientHttpRequestFactory requestFactory(Duration connect, Duration read) {
    var http =
        HttpClient.newBuilder()
            .connectTimeout(connect)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(read);
    return factory;
  }
}
