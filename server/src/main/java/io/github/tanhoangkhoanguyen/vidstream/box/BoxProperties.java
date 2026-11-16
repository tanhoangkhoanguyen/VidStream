package io.github.tanhoangkhoanguyen.vidstream.box;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vidstream.box")
public record BoxProperties(@DefaultValue("https://api.box.com/2.0") URI apiUrl) {}
