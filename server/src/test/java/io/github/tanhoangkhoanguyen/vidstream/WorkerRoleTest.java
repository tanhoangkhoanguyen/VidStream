package io.github.tanhoangkhoanguyen.vidstream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    webEnvironment = WebEnvironment.DEFINED_PORT,
    properties = {"vidstream.role=worker", "management.server.port=0"})
@Import(PostgresTestConfig.class)
class WorkerRoleTest {

  @Autowired WebServerApplicationContext context;

  @LocalManagementPort int managementPort;

  @Test
  void onlyActuatorListens() {
    assertThat(context.getWebServer().getPort()).isEqualTo(-1);

    String health =
        RestClient.create()
            .get()
            .uri("http://localhost:{port}/actuator/health", managementPort)
            .retrieve()
            .body(String.class);
    assertThat(health).contains("\"status\":\"UP\"");
  }
}
