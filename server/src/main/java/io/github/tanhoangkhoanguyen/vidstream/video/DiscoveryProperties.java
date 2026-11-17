package io.github.tanhoangkhoanguyen.vidstream.video;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param videoExtensions lower case; other files (e.g. DJI {@code lrf} proxies) are skipped
 */
@ConfigurationProperties("vidstream.discovery")
public record DiscoveryProperties(
    @DefaultValue({"mp4", "mpg", "mov", "mkv", "avi", "m4v"}) Set<String> videoExtensions) {}
