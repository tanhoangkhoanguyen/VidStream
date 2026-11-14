package io.github.tanhoangkhoanguyen.vidstream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binding the role to an enum makes a typo fail startup instead of activating no profile. */
@ConfigurationProperties("vidstream")
public record VidStreamProperties(Role role) {

  public enum Role {
    WEB,
    WORKER
  }
}
