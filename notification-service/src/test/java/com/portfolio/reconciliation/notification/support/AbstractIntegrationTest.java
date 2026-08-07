package com.portfolio.reconciliation.notification.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base dos testes de integração do notification-service. Containers singleton (Postgres +
 * RabbitMQ) iniciados uma vez e reusados por todos os ITs do JVM (reapados pelo Ryuk ao fim).
 *
 * <p>{@code spring.rabbitmq.listener.simple.auto-startup=false}: desligado por padrão aqui
 * porque cada IT sobe seu próprio contexto Spring (e, portanto, seu próprio
 * {@code DivergenceDetectedListener}) contra o mesmo broker compartilhado do Testcontainers. Com
 * o listener ligado em mais de uma classe, os consumidores concorrem pela mesma fila física e o
 * RabbitMQ faz round-robin das entregas entre eles, roubando mensagens de um IT para outro de
 * forma não determinística. Só a classe que de fato precisa consumir mensagens
 * ({@code NotificationEndToEndIT}) liga essa propriedade de volta via seu próprio {@code
 * @TestPropertySource} — subclasse sobrepõe superclasse para a mesma chave. Propositalmente
 * definido via {@code @TestPropertySource} e não via {@code @DynamicPropertySource}: valores
 * dinâmicos têm precedência incondicional sobre {@code @TestPropertySource} e, entre métodos
 * {@code @DynamicPropertySource} da hierarquia, o da superclasse é invocado por último e vence
 * — ou seja, um valor fixo daqui não seria overridável por uma subclasse por esse mecanismo.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
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
    // Retry rápido nos testes — os ITs de poison message não esperam o backoff de produção.
    registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
    registry.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> "2");
  }
}
