package io.github.tanhoangkhoanguyen.vidstream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Binding the role to an enum makes a typo fail startup instead of activating no profile.
 *
 * @param datasetsFile {@code datasets.yml}, e.g. {@code file:/app/datasets.yml}; web role only
 * @param tokenKey base64 of 32 random bytes; encrypts Box tokens at rest; web role only
 * @param domain public host of VidStream; dataset {@code x} lives on {@code x.<domain>}; web role
 *     only
 */
@ConfigurationProperties("vidstream")
public record VidStreamProperties(
    Role role, Resource datasetsFile, String tokenKey, String domain) {

  public enum Role {
    WEB,
    WORKER
  }
}
