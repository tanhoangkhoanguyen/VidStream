package io.github.tanhoangkhoanguyen.vidstream.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tanhoangkhoanguyen.vidstream.config.VidStreamProperties.Role;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VidStreamPropertiesTest {

  @EnableConfigurationProperties(VidStreamProperties.class)
  static class Config {}

  final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void bindsRole() {
    runner
        .withPropertyValues("vidstream.role=worker")
        .run(ctx ->
            assertThat(ctx.getBean(VidStreamProperties.class).role()).isEqualTo(Role.WORKER));
  }

  @Test
  void unknownRoleFailsStartup() {
    runner.withPropertyValues("vidstream.role=wroker").run(ctx -> assertThat(ctx).hasFailed());
  }
}
