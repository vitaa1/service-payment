package com.portfolio.reconciliation.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.EventPayload;
import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.ReconciliationCompletedPayload;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import com.portfolio.reconciliation.events.routing.EventTypes;
import com.portfolio.reconciliation.events.routing.RoutingKeys;
import com.portfolio.reconciliation.reconciliation.domain.NormalizedRecord;
import com.portfolio.reconciliation.reconciliation.domain.NormalizedRecordRepository;
import com.portfolio.reconciliation.reconciliation.domain.ReconciliationCase;
import com.portfolio.reconciliation.reconciliation.domain.ReconciliationCaseRepository;
import com.portfolio.reconciliation.reconciliation.evaluation.CaseEvaluation;
import com.portfolio.reconciliation.reconciliation.evaluation.CaseEvaluator;
import com.portfolio.reconciliation.reconciliation.outbox.OutboxEvent;
import com.portfolio.reconciliation.reconciliation.outbox.OutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orquestra a reavaliação de um caso a cada {@code TransactionNormalized} recebido (ADR-0010):
 * dedup por {@code eventId}, lock pessimista/criação do caso, avaliação, persistência e
 * gravação dos eventos na outbox — tudo numa única transação.
 */
@Service
public class ReconciliationService {

  private static final String PRODUCER = "reconciliation-service";
  private static final int EVENT_VERSION = 1;

  private final ReconciliationCaseRepository caseRepository;
  private final NormalizedRecordRepository recordRepository;
  private final OutboxRepository outboxRepository;
  private final CaseEvaluator caseEvaluator;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;

  public ReconciliationService(
      ReconciliationCaseRepository caseRepository,
      NormalizedRecordRepository recordRepository,
      OutboxRepository outboxRepository,
      CaseEvaluator caseEvaluator,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.caseRepository = caseRepository;
    this.recordRepository = recordRepository;
    this.outboxRepository = outboxRepository;
    this.caseEvaluator = caseEvaluator;
    this.objectMapper = objectMapper;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Ponto de entrada não-transacional: cada tentativa roda numa transação própria via {@link
   * TransactionTemplate}, evitando o problema de auto-invocação do {@code @Transactional} do
   * Spring. Em corrida na criação do caso (constraint UNIQUE de matching_key), tenta uma segunda
   * vez — a tentativa vencedora já terá commitado, e a nova tentativa encontra o caso via lock
   * (ADR-0010).
   */
  public void handle(EventEnvelope<TransactionNormalizedPayload> envelope) {
    try {
      transactionTemplate.executeWithoutResult(status -> processInTransaction(envelope));
    } catch (DataIntegrityViolationException race) {
      transactionTemplate.executeWithoutResult(status -> processInTransaction(envelope));
    }
  }

  private void processInTransaction(EventEnvelope<TransactionNormalizedPayload> envelope) {
    UUID eventId = envelope.eventId();
    if (recordRepository.findByEventId(eventId).isPresent()) {
      return; // reentrega — idempotência por eventId (ADR-0002)
    }

    TransactionNormalizedPayload payload = envelope.payload();
    String matchingKey = payload.externalReference(); // ADR-0009

    CaseLookup lookup = findOrCreateCase(matchingKey);
    ReconciliationCase reconciliationCase = lookup.reconciliationCase();
    List<NormalizedRecord> existing = recordRepository.findByCaseId(reconciliationCase.getId());

    NormalizedRecord newRecord =
        new NormalizedRecord(
            UUID.randomUUID(),
            eventId,
            reconciliationCase.getId(),
            payload.source(),
            payload.externalReference(),
            payload.amount(),
            payload.currency(),
            payload.transactionDate(),
            envelope.traceId());
    recordRepository.save(newRecord);

    List<NormalizedRecord> allRecords = new ArrayList<>(existing);
    allRecords.add(newRecord);
    CaseEvaluation evaluation = caseEvaluator.evaluate(allRecords);

    // O construtor já define version=1, que representa a PRIMEIRA avaliação (a que cria o
    // caso). Só incrementa em reavaliações de um caso preexistente (ADR-0010: "incrementa a
    // cada reavaliação" — a primeira avaliação não é uma reavaliação).
    if (!lookup.created()) {
      reconciliationCase.setVersion(reconciliationCase.getVersion() + 1);
    }
    reconciliationCase.setStatus(evaluation.status());
    reconciliationCase.setDivergenceDetails(
        evaluation.details() == null ? null : toJson(evaluation.details()));
    reconciliationCase.setUpdatedAt(Instant.now());
    caseRepository.save(reconciliationCase);

    emitEvents(reconciliationCase, evaluation, payload, envelope.traceId());
  }

  /** Se {@code created}, o {@code version} do caso ainda é o valor inicial do construtor (1). */
  private record CaseLookup(ReconciliationCase reconciliationCase, boolean created) {}

  /**
   * Trava a linha do caso ({@code FOR UPDATE}); se não existir, cria. A criação pode colidir com
   * outra transação concorrente criando o mesmo caso — deixa a {@link
   * DataIntegrityViolationException} propagar para o {@link #handle} tratar como replay.
   */
  private CaseLookup findOrCreateCase(String matchingKey) {
    Optional<ReconciliationCase> existing = caseRepository.lockByMatchingKey(matchingKey);
    if (existing.isPresent()) {
      return new CaseLookup(existing.get(), false);
    }
    // MISSING é só o status inicial exigido pelo construtor — nunca fica visível: a linha só
    // existe dentro desta transação, que sempre a sobrescreve com o resultado real da
    // avaliação antes de retornar (ver processInTransaction).
    ReconciliationCase created =
        caseRepository.saveAndFlush(
            new ReconciliationCase(UUID.randomUUID(), matchingKey, ReconciliationStatus.MISSING));
    return new CaseLookup(created, true);
  }

  private void emitEvents(
      ReconciliationCase reconciliationCase,
      CaseEvaluation evaluation,
      TransactionNormalizedPayload payload,
      UUID legTraceId) {
    Instant now = Instant.now();

    ReconciliationCompletedPayload completedPayload =
        new ReconciliationCompletedPayload(
            reconciliationCase.getId(),
            reconciliationCase.getMatchingKey(),
            evaluation.status(),
            reconciliationCase.getVersion(),
            payload.externalReference(),
            evaluation.amount(),
            evaluation.currency(),
            evaluation.transactionDate(),
            evaluation.sourcesPresent(),
            now);
    saveOutboxEvent(
        EventTypes.RECONCILIATION_COMPLETED,
        RoutingKeys.RECONCILIATION_COMPLETED,
        legTraceId,
        envelopeFor(EventTypes.RECONCILIATION_COMPLETED, now, legTraceId, completedPayload));

    if (evaluation.status() != ReconciliationStatus.MATCHED) {
      DivergenceType divergenceType = DivergenceType.valueOf(evaluation.status().name());
      DivergenceDetectedPayload divergencePayload =
          new DivergenceDetectedPayload(
              reconciliationCase.getId(),
              reconciliationCase.getMatchingKey(),
              divergenceType,
              reconciliationCase.getVersion(),
              payload.externalReference(),
              evaluation.details(),
              now);
      saveOutboxEvent(
          EventTypes.DIVERGENCE_DETECTED,
          RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED,
          legTraceId,
          envelopeFor(EventTypes.DIVERGENCE_DETECTED, now, legTraceId, divergencePayload));
    }
  }

  private <T extends EventPayload> EventEnvelope<T> envelopeFor(
      String eventType, Instant occurredAt, UUID traceId, T payload) {
    return new EventEnvelope<>(
        UUID.randomUUID(), eventType, EVENT_VERSION, occurredAt, traceId, PRODUCER, payload);
  }

  private void saveOutboxEvent(
      String eventType, String routingKey, UUID traceId, EventEnvelope<?> envelope) {
    outboxRepository.save(
        new OutboxEvent(
            envelope.eventId(), eventType, routingKey, traceId, toJson(envelope)));
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("falha ao serializar para JSON", e);
    }
  }
}
