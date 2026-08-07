package com.portfolio.reconciliation.reconciliation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.reconciliation.outbox.OutboxEvent;
import com.portfolio.reconciliation.reconciliation.outbox.OutboxRepository;
import com.portfolio.reconciliation.reconciliation.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Fatia de persistência: migration Flyway sobe o schema e as entidades persistem/recuperam. */
class ReconciliationPersistenceIT extends AbstractIntegrationTest {

  @Autowired ReconciliationCaseRepository caseRepository;
  @Autowired NormalizedRecordRepository recordRepository;
  @Autowired OutboxRepository outboxRepository;

  @Test
  void devePersistirERecuperarCaso() {
    UUID id = UUID.randomUUID();
    String key = "chg_" + id;
    caseRepository.saveAndFlush(new ReconciliationCase(id, key, ReconciliationStatus.MISSING));

    ReconciliationCase loaded = caseRepository.findById(id).orElseThrow();
    assertEquals(key, loaded.getMatchingKey());
    assertEquals(ReconciliationStatus.MISSING, loaded.getStatus());
    assertEquals(1, loaded.getVersion());
    assertNotNull(loaded.getCreatedAt());
  }

  @Test
  void deveImporUniqueNaMatchingKey() {
    String key = "dup-" + UUID.randomUUID();
    caseRepository.saveAndFlush(
        new ReconciliationCase(UUID.randomUUID(), key, ReconciliationStatus.MISSING));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            caseRepository.saveAndFlush(
                new ReconciliationCase(UUID.randomUUID(), key, ReconciliationStatus.MATCHED)));
  }

  @Test
  void devePersistirNormalizedRecordLigadoAoCasoEBuscarPorEventIdEPorCaso() {
    UUID caseId = UUID.randomUUID();
    String key = "chg_" + caseId;
    caseRepository.saveAndFlush(new ReconciliationCase(caseId, key, ReconciliationStatus.MISSING));

    UUID eventId = UUID.randomUUID();
    recordRepository.saveAndFlush(
        new NormalizedRecord(
            UUID.randomUUID(),
            eventId,
            caseId,
            Source.GATEWAY,
            key,
            new BigDecimal("199.90"),
            "BRL",
            LocalDate.parse("2026-07-29"),
            UUID.randomUUID()));

    assertEquals(true, recordRepository.findByEventId(eventId).isPresent());
    assertEquals(1, recordRepository.findByCaseId(caseId).size());
    NormalizedRecord loaded = recordRepository.findByEventId(eventId).orElseThrow();
    assertEquals(Source.GATEWAY, loaded.getSource());
    assertEquals(new BigDecimal("199.9000"), loaded.getAmount());
  }

  @Test
  void deveImporUniqueNoEventId() {
    UUID caseId = UUID.randomUUID();
    caseRepository.saveAndFlush(
        new ReconciliationCase(caseId, "chg_" + caseId, ReconciliationStatus.MISSING));
    UUID eventId = UUID.randomUUID();

    recordRepository.saveAndFlush(novoRecord(eventId, caseId));

    assertThrows(
        DataIntegrityViolationException.class,
        () -> recordRepository.saveAndFlush(novoRecord(eventId, caseId)));
  }

  @Test
  void devePersistirOutboxComIdGerado() {
    UUID eventId = UUID.randomUUID();
    OutboxEvent event =
        new OutboxEvent(
            eventId,
            "ReconciliationCompleted",
            "reconciliation.completed",
            UUID.randomUUID(),
            "{\"eventId\":\"" + eventId + "\"}");

    outboxRepository.saveAndFlush(event);

    assertNotNull(event.getId(), "id BIGSERIAL gerado pelo banco");
    assertNotNull(outboxRepository.findById(event.getId()).orElseThrow().getCreatedAt());
  }

  private NormalizedRecord novoRecord(UUID eventId, UUID caseId) {
    return new NormalizedRecord(
        UUID.randomUUID(),
        eventId,
        caseId,
        Source.GATEWAY,
        "chg_" + caseId,
        new BigDecimal("10.00"),
        "BRL",
        LocalDate.parse("2026-07-29"),
        UUID.randomUUID());
  }
}
