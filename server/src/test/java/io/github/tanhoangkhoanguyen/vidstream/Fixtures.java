package io.github.tanhoangkhoanguyen.vidstream;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;

/** Recorded API responses (Phase 0, sanitized). Stubs use these, never hand-written bodies. */
public final class Fixtures {

  private Fixtures() {}

  /** E.g. {@code read("box/error-404.json")}. */
  public static String read(String path) {
    try {
      return new ClassPathResource("fixtures/" + path).getContentAsString(UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
