package io.github.tanhoangkhoanguyen.vidstream;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    webEnvironment = WebEnvironment.DEFINED_PORT,
    properties = {"vidstream.role=worker", "management.server.port=0"})
class WorkerRoleIT {

  @Autowired WebServerApplicationContext context;

  @LocalManagementPort int managementPort;

  /** Started without Postgres: workers reach the queue through the worker API only. */
  @Test
  void hasNoDatabase() {
    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
  }

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
