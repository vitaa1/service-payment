package com.portfolio.reconciliation.ingestion.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import com.portfolio.reconciliation.events.routing.EventTypes;
import com.portfolio.reconciliation.events.routing.RoutingKeys;
import com.portfolio.reconciliation.ingestion.normalization.NormalizerRegistry;
import com.portfolio.reconciliation.ingestion.normalization.SourceNormalizer;
import com.portfolio.reconciliation.ingestion.outbox.OutboxEvent;
import com.portfolio.reconciliation.ingestion.outbox.OutboxRepository;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestion;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestionRepository;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestionStatus;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra a ingestão: idempotência, validação síncrona, normalização e gravação atômica de
 * raw_ingestion + outbox (ADR-0006). Ver docs/ingestion/source-formats.md.
 */
@Service
public class IngestionService {

  private static final String PRODUCER = "ingestion-service";
  private static final int EVENT_VERSION = 1;
  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

  private final RawIngestionRepository rawRepository;
  private final OutboxRepository outboxRepository;
  private final NormalizerRegistry registry;
  private final ObjectMapper objectMapper;
  private final Validator validator;

  public IngestionService(
      RawIngestionRepository rawRepository,
      OutboxRepository outboxRepository,
      NormalizerRegistry registry,
      ObjectMapper objectMapper,
      Validator validator) {
    this.rawRepository = rawRepository;
    this.outboxRepository = outboxRepository;
    this.registry = registry;
    this.objectMapper = objectMapper;
    this.validator = validator;
  }

  @Transactional
  public IngestionResult ingest(Source source, String rawBody, String idempotencyKey) {
    UUID ingestionId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();

    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
        return IngestionResult.rejected(
            ingestionId,
            traceId,
            List.of("Idempotency-Key excede " + MAX_IDEMPOTENCY_KEY_LENGTH + " caracteres"));
      }
      Optional<RawIngestion> existing = rawRepository.findByIdempotencyKey(idempotencyKey);
      if (existing.isPresent()) {
        return replayDe(existing.get());
      }
    }

    Instant now = Instant.now();
    SourceNormalizer<?> normalizer = registry.forSource(source);

    JsonNode tree;
    try {
      tree = objectMapper.readTree(rawBody);
    } catch (JsonProcessingException ex) {
      // Corpo não é JSON válido: não dá para auditar numa coluna JSONB; rejeita sem persistir.
      return IngestionResult.rejected(ingestionId, traceId, List.of("corpo não é um JSON válido"));
    }

    Object dto;
    try {
      dto = objectMapper.treeToValue(tree, normalizer.requestType());
    } catch (JsonProcessingException ex) {
      List<String> errors = List.of("payload incompatível com a fonte " + source);
      persistRejected(ingestionId, source, rawBody, idempotencyKey, traceId, errors);
      return IngestionResult.rejected(ingestionId, traceId, errors);
    }

    List<String> violations = validate(dto);
    if (!violations.isEmpty()) {
      persistRejected(ingestionId, source, rawBody, idempotencyKey, traceId, violations);
      return IngestionResult.rejected(ingestionId, traceId, violations);
    }

    TransactionNormalizedPayload payload = normalize(normalizer, ingestionId, dto);
    UUID eventId = UUID.randomUUID();
    EventEnvelope<TransactionNormalizedPayload> envelope =
        new EventEnvelope<>(
            eventId,
            EventTypes.TRANSACTION_NORMALIZED,
            EVENT_VERSION,
            now,
            traceId,
            PRODUCER,
            payload);

    RawIngestion raw =
        new RawIngestion(
            ingestionId,
            source,
            rawBody,
            RawIngestionStatus.VALIDATED,
            idempotencyKey,
            traceId,
            now);
    raw.setPublishedEventId(eventId);
    rawRepository.save(raw);
    outboxRepository.save(
        new OutboxEvent(
            eventId,
            EventTypes.TRANSACTION_NORMALIZED,
            RoutingKeys.TRANSACTION_NORMALIZED,
            traceId,
            serialize(envelope)));

    return IngestionResult.accepted(ingestionId, traceId);
  }

  /**
   * Replay pós-corrida: se duas requisições com a mesma key correm, uma insere e a outra viola a
   * constraint UNIQUE (ADR-0008). O controller captura a violação e chama isto para devolver o
   * registro já gravado, em vez de estourar 500.
   */
  @Transactional(readOnly = true)
  public IngestionResult replayByKey(String idempotencyKey) {
    return rawRepository
        .findByIdempotencyKey(idempotencyKey)
        .map(this::replayDe)
        .orElseThrow(
            () -> new IllegalStateException("corrida de idempotência sem registro para a key"));
  }

  /** Replay de idempotência: devolve o resultado equivalente ao registro já processado. */
  private IngestionResult replayDe(RawIngestion existing) {
    if (existing.getStatus() == RawIngestionStatus.VALIDATED) {
      return IngestionResult.accepted(existing.getId(), existing.getTraceId());
    }
    return IngestionResult.rejected(existing.getId(), existing.getTraceId(), errosDe(existing));
  }

  private List<String> errosDe(RawIngestion raw) {
    if (raw.getValidationErrors() == null) {
      return List.of();
    }
    try {
      return objectMapper.readValue(
          raw.getValidationErrors(), new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private List<String> validate(Object dto) {
    return validator.validate(dto).stream()
        .map(v -> v.getPropertyPath() + " " + v.getMessage())
        .sorted()
        .toList();
  }

  private void persistRejected(
      UUID id,
      Source source,
      String rawBody,
      String idempotencyKey,
      UUID traceId,
      List<String> errors) {
    RawIngestion raw =
        new RawIngestion(
            id, source, rawBody, RawIngestionStatus.REJECTED, idempotencyKey, traceId, Instant.now());
    raw.setValidationErrors(serialize(errors));
    rawRepository.save(raw);
  }

  @SuppressWarnings("unchecked")
  private TransactionNormalizedPayload normalize(SourceNormalizer<?> normalizer, UUID id, Object dto) {
    return ((SourceNormalizer<Object>) normalizer).normalize(id, dto);
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("falha ao serializar para JSON", e);
    }
  }
}
