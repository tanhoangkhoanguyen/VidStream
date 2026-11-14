package io.github.tanhoangkhoanguyen.vidstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VidStreamApplication {

  public static void main(String[] args) {
    SpringApplication.run(VidStreamApplication.class, args);
  }
}
