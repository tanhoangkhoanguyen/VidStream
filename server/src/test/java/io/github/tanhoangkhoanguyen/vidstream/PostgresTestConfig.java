package io.github.tanhoangkhoanguyen.vidstream;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

  // One container for every test context; Ryuk removes it when the JVM exits.
  // Room for the queue test's 100 claimers on their own connections.
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17")
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200");

  @Bean(destroyMethod = "")
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return POSTGRES;
  }
}
