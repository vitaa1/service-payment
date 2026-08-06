package com.portfolio.reconciliation.events.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden JSON: a fixture em test/resources/contracts/transaction-normalized.json é cópia
 * reviewada do exemplo em docs/events/transaction-normalized.md (fonte da verdade).
 * O event-contract-checker garante que as duas permanecem sincronizadas.
 */
class TransactionNormalizedGoldenJsonTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private JsonNode readFixture() throws Exception {
    return readFixture("/contracts/transaction-normalized.json");
  }

  private JsonNode readFixture(String resourcePath) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      return mapper.readTree(in);
    }
  }

  @Test
  void deveDesserializarFixtureComTodosOsCamposCorretos() throws Exception {
    EventEnvelope<TransactionNormalizedPayload> envelope =
        mapper.readValue(
            readFixture().traverse(),
            new TypeReference<EventEnvelope<TransactionNormalizedPayload>>() {});

    assertEquals(UUID.fromString("b4f1e6a0-9c2d-4a7e-8f13-2a6d5c1e9b00"), envelope.eventId());
    assertEquals("TransactionNormalized", envelope.eventType());
    assertEquals(1, envelope.eventVersion());
    assertEquals("ingestion-service", envelope.producer());

    TransactionNormalizedPayload payload = envelope.payload();
    assertEquals(Source.GATEWAY, payload.source());
    assertEquals("chg_9f8e7d6c5b4a", payload.externalReference());
    assertEquals(new BigDecimal("199.90"), payload.amount());
    assertEquals("BRL", payload.currency());
    assertEquals(LocalDate.parse("2026-07-29"), payload.transactionDate());
    assertEquals("Loja Exemplo LTDA", payload.counterparty());
    assertEquals("txn_abc123", payload.sourceMetadata().get("gatewayTxnId"));
  }

  @Test
  void deveRoundtripSerializandoDeVoltaParaOMesmoJson() throws Exception {
    JsonNode original = readFixture();

    EventEnvelope<TransactionNormalizedPayload> envelope =
        mapper.readValue(
            original.traverse(),
            new TypeReference<EventEnvelope<TransactionNormalizedPayload>>() {});

    JsonNode roundTripped = mapper.valueToTree(envelope);

    assertEquals(original, roundTripped);
  }

  @Test
  void deveDesserializarSemCamposOpcionaisPresentes() throws Exception {
    JsonNode original = readFixture("/contracts/transaction-normalized-sem-opcionais.json");

    EventEnvelope<TransactionNormalizedPayload> envelope =
        mapper.readValue(
            original.traverse(),
            new TypeReference<EventEnvelope<TransactionNormalizedPayload>>() {});

    assertEquals(null, envelope.payload().counterparty());
    assertEquals(null, envelope.payload().sourceMetadata());

    JsonNode roundTripped = mapper.valueToTree(envelope);

    assertEquals(original, roundTripped);
  }
}
