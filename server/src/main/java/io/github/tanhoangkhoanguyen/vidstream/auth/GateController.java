package io.github.tanhoangkhoanguyen.vidstream.auth;

import io.github.tanhoangkhoanguyen.vidstream.config.VidStreamProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caddy {@code forward_auth} for {@code <dataset>.<domain>}: 200 lets the request into that
 * dataset's Label Studio, 401 blocks it. Session only, no Box call: it runs on every request.
 */
@RestController
@Profile("web")
class GateController {

  static final String PATH = "/internal/gate";

  private final String suffix;

  GateController(VidStreamProperties props) {
    this.suffix =
        "."
            + Objects.requireNonNull(props.domain(), "vidstream.domain is not set")
                .toLowerCase(Locale.ROOT);
  }

  // The host is Caddy's X-Forwarded-Host (Tomcat trusts it from private addresses only).
  @GetMapping(PATH)
  ResponseEntity<Void> gate(HttpServletRequest request, Authentication auth) {
    var host = request.getServerName().toLowerCase(Locale.ROOT);
    if (auth == null || !host.endsWith(suffix)) return status(false);
    var authority = BoxUserService.DATASET + host.substring(0, host.length() - suffix.length());
    return status(auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority())));
  }

  private static ResponseEntity<Void> status(boolean allowed) {
    return ResponseEntity.status(allowed ? HttpStatus.OK : HttpStatus.UNAUTHORIZED).build();
  }
}
