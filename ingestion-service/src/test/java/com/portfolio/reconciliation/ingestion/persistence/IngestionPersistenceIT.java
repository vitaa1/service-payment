package com.portfolio.reconciliation.ingestion.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.ingestion.outbox.OutboxEvent;
import com.portfolio.reconciliation.ingestion.outbox.OutboxRepository;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestion;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestionRepository;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fatia de persistência: confirma que a migration Flyway sobe o schema e que as entidades
 * (raw_ingestion, outbox) persistem e recuperam corretamente, incluindo as constraints.
 */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration")
@Testcontainers
class IngestionPersistenceIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired RawIngestionRepository rawIngestionRepository;
  @Autowired OutboxRepository outboxRepository;

  @Test
  void devePersistirERecuperarRawIngestion() {
    UUID id = UUID.randomUUID();
    RawIngestion raw =
        new RawIngestion(
            id,
            Source.GATEWAY,
            "{\"chargeId\":\"chg_1\"}",
            RawIngestionStatus.VALIDATED,
            "idem-" + id,
            UUID.randomUUID(),
            Instant.now());

    rawIngestionRepository.saveAndFlush(raw);

    RawIngestion loaded = rawIngestionRepository.findById(id).orElseThrow();
    assertEquals(Source.GATEWAY, loaded.getSource());
    assertEquals(RawIngestionStatus.VALIDATED, loaded.getStatus());
    assertEquals("{\"chargeId\": \"chg_1\"}", loaded.getRawPayload());
    assertNotNull(loaded.getReceivedAt());
  }

  @Test
  void deveImporUniqueNaIdempotencyKey() {
    String key = "dup-key";
    rawIngestionRepository.saveAndFlush(novoRaw(key));

    assertThrows(
        DataIntegrityViolationException.class,
        () -> rawIngestionRepository.saveAndFlush(novoRaw(key)));
  }

  @Test
  void devePersistirOutboxComIdGeradoEEventIdUnico() {
    UUID eventId = UUID.randomUUID();
    OutboxEvent event =
        new OutboxEvent(
            eventId,
            "TransactionNormalized",
            "transaction.normalized",
            UUID.randomUUID(),
            "{\"eventId\":\"" + eventId + "\"}");

    outboxRepository.saveAndFlush(event);

    assertNotNull(event.getId(), "id BIGSERIAL deve ser gerado pelo banco");
    OutboxEvent loaded = outboxRepository.findById(event.getId()).orElseThrow();
    assertEquals(eventId, loaded.getEventId());
    assertNotNull(loaded.getCreatedAt(), "created_at deve vir do default do banco");
    assertEquals(0, loaded.getAttempts());
  }

  private RawIngestion novoRaw(String idempotencyKey) {
    return new RawIngestion(
        UUID.randomUUID(),
        Source.BANK_STATEMENT,
        "{}",
        RawIngestionStatus.RECEIVED,
        idempotencyKey,
        UUID.randomUUID(),
        Instant.now());
  }
}
