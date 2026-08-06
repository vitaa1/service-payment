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
import com.portfolio.reconciliation.ingestion.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Confirma que a migration Flyway sobe o schema e que raw_ingestion/outbox persistem. */
class IngestionPersistenceIT extends AbstractIntegrationTest {

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
    String key = "dup-key-" + UUID.randomUUID();
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
