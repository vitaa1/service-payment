package com.portfolio.reconciliation.reconciliation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import com.portfolio.reconciliation.events.routing.EventTypes;
import com.portfolio.reconciliation.reconciliation.domain.NormalizedRecordRepository;
import com.portfolio.reconciliation.reconciliation.domain.ReconciliationCase;
import com.portfolio.reconciliation.reconciliation.domain.ReconciliationCaseRepository;
import com.portfolio.reconciliation.reconciliation.outbox.OutboxEvent;
import com.portfolio.reconciliation.reconciliation.outbox.OutboxRepository;
import com.portfolio.reconciliation.reconciliation.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationServiceIT extends AbstractIntegrationTest {

  @Autowired ReconciliationService service;
  @Autowired ReconciliationCaseRepository caseRepository;
  @Autowired NormalizedRecordRepository recordRepository;
  @Autowired OutboxRepository outboxRepository;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void limpa() {
    outboxRepository.deleteAll();
    recordRepository.deleteAll();
    caseRepository.deleteAll();
  }

  private EventEnvelope<TransactionNormalizedPayload> envelope(
      Source source, String externalReference, String amount, String currency, LocalDate date) {
    TransactionNormalizedPayload payload =
        new TransactionNormalizedPayload(
            UUID.randomUUID(),
            source,
            externalReference,
            new BigDecimal(amount),
            currency,
            date,
            null,
            null);
    return new EventEnvelope<>(
        UUID.randomUUID(),
        EventTypes.TRANSACTION_NORMALIZED,
        1,
        Instant.now(),
        UUID.randomUUID(),
        "ingestion-service",
        payload);
  }

  @Test
  void primeiraPernaCriaCasoMissingEEmiteCompletedEDivergence() {
    String ref = "chg_" + UUID.randomUUID();
    service.handle(envelope(Source.GATEWAY, ref, "199.90", "BRL", LocalDate.parse("2026-07-29")));

    ReconciliationCase caseRow = caseRepository.findByMatchingKey(ref).orElseThrow();
    assertEquals(ReconciliationStatus.MISSING, caseRow.getStatus());
    assertEquals(1, caseRow.getVersion());

    List<OutboxEvent> events = outboxRepository.findAll();
    assertEquals(2, events.size(), "ReconciliationCompleted + DivergenceDetected");
    assertTrue(events.stream().anyMatch(e -> e.getEventType().equals(EventTypes.RECONCILIATION_COMPLETED)));
    assertTrue(events.stream().anyMatch(e -> e.getEventType().equals(EventTypes.DIVERGENCE_DETECTED)));
  }

  @Test
  void segundaPernaConsistenteReavaliaParaMatchedESoEmiteCompleted() {
    String ref = "chg_" + UUID.randomUUID();
    service.handle(envelope(Source.GATEWAY, ref, "199.90", "BRL", LocalDate.parse("2026-07-29")));
    service.handle(
        envelope(Source.INTERNAL_ORDER, ref, "199.90", "BRL", LocalDate.parse("2026-07-29")));

    ReconciliationCase caseRow = caseRepository.findByMatchingKey(ref).orElseThrow();
    assertEquals(ReconciliationStatus.MATCHED, caseRow.getStatus());
    assertEquals(2, caseRow.getVersion());

    List<OutboxEvent> events = outboxRepository.findAll();
    assertEquals(3, events.size(), "1o leg: Completed+Divergence; 2o leg: só Completed");
  }

  @Test
  void pernaDivergenteGeraDivergenceComFieldAmount() throws Exception {
    String ref = "chg_" + UUID.randomUUID();
    service.handle(envelope(Source.GATEWAY, ref, "199.90", "BRL", LocalDate.parse("2026-07-29")));
    service.handle(
        envelope(Source.INTERNAL_ORDER, ref, "189.90", "BRL", LocalDate.parse("2026-07-29")));

    ReconciliationCase caseRow = caseRepository.findByMatchingKey(ref).orElseThrow();
    assertEquals(ReconciliationStatus.DIVERGENT, caseRow.getStatus());

    OutboxEvent divergence =
        outboxRepository.findAll().stream()
            .filter(e -> e.getEventType().equals(EventTypes.DIVERGENCE_DETECTED))
            .reduce((first, second) -> second) // a mais recente
            .orElseThrow();
    JsonNode json = objectMapper.readTree(divergence.getPayload());
    assertEquals("amount", json.get("payload").get("details").get("field").asText());
  }

  @Test
  void reentregaDoMesmoEventIdNaoDuplicaNemRepublica() {
    String ref = "chg_" + UUID.randomUUID();
    EventEnvelope<TransactionNormalizedPayload> first =
        envelope(Source.GATEWAY, ref, "199.90", "BRL", LocalDate.parse("2026-07-29"));
    // Reentrega: mesmo eventId, mesmo payload.
    EventEnvelope<TransactionNormalizedPayload> replay =
        new EventEnvelope<>(
            first.eventId(),
            first.eventType(),
            first.eventVersion(),
            first.occurredAt(),
            first.traceId(),
            first.producer(),
            first.payload());

    service.handle(first);
    service.handle(replay);

    assertEquals(1, recordRepository.findByCaseId(caseRepository.findByMatchingKey(ref).orElseThrow().getId()).size());
    assertEquals(2, outboxRepository.count(), "reentrega não gera novos eventos");
    assertEquals(1, caseRepository.findByMatchingKey(ref).orElseThrow().getVersion());
  }

  @Test
  void duasPernasConcorrentesParaOMesmoCasoNovoConvergem() throws Exception {
    String ref = "chg_" + UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);

    Runnable gateway =
        () -> {
          ready.countDown();
          await(go);
          service.handle(envelope(Source.GATEWAY, ref, "199.90", "BRL", LocalDate.parse("2026-07-29")));
        };
    Runnable order =
        () -> {
          ready.countDown();
          await(go);
          service.handle(
              envelope(Source.INTERNAL_ORDER, ref, "199.90", "BRL", LocalDate.parse("2026-07-29")));
        };

    pool.submit(gateway);
    pool.submit(order);
    ready.await(5, TimeUnit.SECONDS);
    go.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

    assertEquals(1, caseRepository.findAll().stream().filter(c -> c.getMatchingKey().equals(ref)).count());
    ReconciliationCase caseRow = caseRepository.findByMatchingKey(ref).orElseThrow();
    assertEquals(2, recordRepository.findByCaseId(caseRow.getId()).size());
    assertEquals(2, caseRow.getVersion(), "as duas reavaliações devem ter incrementado a versão sem se perder");
    assertEquals(ReconciliationStatus.MATCHED, caseRow.getStatus());
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
