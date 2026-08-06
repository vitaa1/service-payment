package com.portfolio.reconciliation.ingestion.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base dos testes de integração. Containers singleton (Postgres + RabbitMQ) iniciados uma vez e
 * reusados por todos os ITs do JVM (reapados pelo Ryuk ao fim). O relay/purge da outbox têm o
 * intervalo elevado aqui para não competir com as chamadas diretas dos testes.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(
          DockerImageName.parse("rabbitmq:3.13-management-alpine")
              .asCompatibleSubstituteFor("rabbitmq"));

  static {
    POSTGRES.start();
    RABBIT.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    registry.add("ingestion.outbox.relay.interval-ms", () -> "3600000");
    registry.add("ingestion.outbox.purge.interval-ms", () -> "3600000");
  }
}
