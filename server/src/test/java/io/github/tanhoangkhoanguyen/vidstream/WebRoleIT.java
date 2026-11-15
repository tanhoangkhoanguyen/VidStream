package io.github.tanhoangkhoanguyen.vidstream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "vidstream.role=web",
      "vidstream.datasets-file=classpath:datasets.yml",
      "management.server.port=0"
    })
@Import(PostgresTestConfig.class)
class WebRoleIT {

  @LocalServerPort int port;

  @LocalManagementPort int managementPort;

  RestTestClient client(int port) {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void managementExposesOnlyHealthInfoPrometheus() {
    var management = client(managementPort);
    management
        .get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
    management.get().uri("/actuator/info").exchange().expectStatus().isOk();
    management.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    management.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
  }

  @Test
  void appPortHasNoActuator() {
    client(port).get().uri("/actuator/health").exchange().expectStatus().isNotFound();
  }
}
