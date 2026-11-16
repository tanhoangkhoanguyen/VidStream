package io.github.tanhoangkhoanguyen.vidstream.web;

import io.github.tanhoangkhoanguyen.vidstream.auth.DatasetAuthorities;
import io.github.tanhoangkhoanguyen.vidstream.config.VidStreamProperties;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/** Pages show only the datasets granted at sign-in; any other dataset does not exist (404). */
@Controller
@Profile("web")
class PagesController {

  private final String domain;

  PagesController(VidStreamProperties props) {
    this.domain = Objects.requireNonNull(props.domain(), "vidstream.domain is not set");
  }

  @GetMapping("/")
  String home(Authentication auth, Model model) {
    model.addAttribute("datasets", DatasetAuthorities.names(auth));
    return "home";
  }

  @GetMapping("/datasets/{name}")
  String dataset(@PathVariable String name, Authentication auth, Model model) {
    if (!DatasetAuthorities.names(auth).contains(name))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    model.addAttribute("name", name);
    model.addAttribute("labelStudio", "https://" + name + "." + domain + "/");
    return "dataset";
  }
}
