package com.portfolio.reconciliation.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void deveSerializarOccurredAtComoIso8601Utc() throws Exception {
    TransactionNormalizedPayload payload =
        new TransactionNormalizedPayload(
            UUID.fromString("8a2b1c3d-4e5f-6071-8293-a4b5c6d7e8f9"),
            Source.GATEWAY,
            "chg_9f8e7d6c5b4a",
            new BigDecimal("199.90"),
            "BRL",
            LocalDate.parse("2026-07-29"),
            null,
            null);

    EventEnvelope<TransactionNormalizedPayload> envelope =
        new EventEnvelope<>(
            UUID.fromString("b4f1e6a0-9c2d-4a7e-8f13-2a6d5c1e9b00"),
            "TransactionNormalized",
            1,
            Instant.parse("2026-07-29T13:45:12.482Z"),
            UUID.fromString("1f0c8a2e-77b4-4d51-9a3c-6e2b0d4f8a11"),
            "ingestion-service",
            payload);

    String json = mapper.writeValueAsString(envelope);

    assertEquals(true, json.contains("\"occurredAt\":\"2026-07-29T13:45:12.482Z\""));
  }

  @Test
  void deveIgnorarCampoDesconhecidoNaDesserializacao() throws Exception {
    String jsonComCampoExtra =
        "{"
            + "\"eventId\":\"b4f1e6a0-9c2d-4a7e-8f13-2a6d5c1e9b00\","
            + "\"eventType\":\"TransactionNormalized\","
            + "\"eventVersion\":1,"
            + "\"occurredAt\":\"2026-07-29T13:45:12.482Z\","
            + "\"traceId\":\"1f0c8a2e-77b4-4d51-9a3c-6e2b0d4f8a11\","
            + "\"producer\":\"ingestion-service\","
            + "\"campoNovoDesconhecido\":\"deve ser ignorado\","
            + "\"payload\":{"
            + "\"ingestionId\":\"8a2b1c3d-4e5f-6071-8293-a4b5c6d7e8f9\","
            + "\"source\":\"GATEWAY\","
            + "\"externalReference\":\"chg_9f8e7d6c5b4a\","
            + "\"amount\":\"199.90\","
            + "\"currency\":\"BRL\","
            + "\"transactionDate\":\"2026-07-29\""
            + "}"
            + "}";

    EventEnvelope<TransactionNormalizedPayload> envelope =
        mapper.readValue(
            jsonComCampoExtra,
            new com.fasterxml.jackson.core.type.TypeReference<
                EventEnvelope<TransactionNormalizedPayload>>() {});

    assertEquals("TransactionNormalized", envelope.eventType());
  }
}
