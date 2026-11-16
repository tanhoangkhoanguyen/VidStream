package io.github.tanhoangkhoanguyen.vidstream.web;

import io.github.tanhoangkhoanguyen.vidstream.auth.DatasetAuthorities;
import io.github.tanhoangkhoanguyen.vidstream.config.VidStreamProperties;
import io.github.tanhoangkhoanguyen.vidstream.video.VideoScan;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Pages show only the datasets granted at sign-in; any other dataset does not exist (404). */
@Controller
@Profile("web")
class PagesController {

  private final String domain;
  private final VideoScan scan;

  PagesController(VidStreamProperties props, VideoScan scan) {
    this.domain = Objects.requireNonNull(props.domain(), "vidstream.domain is not set");
    this.scan = scan;
  }

  @GetMapping("/")
  String home(Authentication auth, Model model) {
    model.addAttribute("datasets", DatasetAuthorities.names(auth));
    return "home";
  }

  @GetMapping("/datasets/{name}")
  String dataset(@PathVariable String name, Authentication auth, Model model) {
    requireMember(name, auth);
    model.addAttribute("name", name);
    model.addAttribute("labelStudio", "https://" + name + "." + domain + "/");
    return "dataset";
  }

  @PostMapping("/datasets/{name}/scan")
  String scan(@PathVariable String name, Authentication auth, RedirectAttributes flash) {
    requireMember(name, auth);
    flash.addFlashAttribute(
        "notice",
        scan.start(name)
            ? "Scan started: new videos appear within minutes."
            : "No scan started: one is already running, or the dataset is no longer active.");
    return "redirect:/datasets/{name}";
  }

  private static void requireMember(String dataset, Authentication auth) {
    if (!DatasetAuthorities.names(auth).contains(dataset))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
