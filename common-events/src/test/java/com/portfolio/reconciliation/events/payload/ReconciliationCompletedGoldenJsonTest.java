package com.portfolio.reconciliation.events.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import java.io.InputStream;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Golden JSON: as fixtures em test/resources/contracts/reconciliation-completed*.json são cópia
 * reviewada do exemplo em docs/events/reconciliation-completed.md (fonte da verdade).
 */
class ReconciliationCompletedGoldenJsonTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private JsonNode readFixture(String resourcePath) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      return mapper.readTree(in);
    }
  }

  @Test
  void deveDesserializarFixtureComTodosOsCamposCorretos() throws Exception {
    JsonNode original = readFixture("/contracts/reconciliation-completed.json");

    EventEnvelope<ReconciliationCompletedPayload> envelope =
        mapper.readValue(
            original.traverse(),
            new TypeReference<EventEnvelope<ReconciliationCompletedPayload>>() {});

    ReconciliationCompletedPayload payload = envelope.payload();
    assertEquals(ReconciliationStatus.MATCHED, payload.status());
    assertEquals(3, payload.caseVersion());
    assertEquals(new BigDecimal("199.90"), payload.amount());
    assertEquals(3, payload.sourcesPresent().size());
    assertEquals(Source.GATEWAY, payload.sourcesPresent().get(0));

    JsonNode roundTripped = mapper.valueToTree(envelope);
    assertEquals(original, roundTripped);
  }

  @Test
  void deveDesserializarSemCamposOpcionaisPresentes() throws Exception {
    JsonNode original = readFixture("/contracts/reconciliation-completed-sem-opcionais.json");

    EventEnvelope<ReconciliationCompletedPayload> envelope =
        mapper.readValue(
            original.traverse(),
            new TypeReference<EventEnvelope<ReconciliationCompletedPayload>>() {});

    ReconciliationCompletedPayload payload = envelope.payload();
    assertEquals(ReconciliationStatus.MISSING, payload.status());
    assertEquals(null, payload.amount());
    assertEquals(null, payload.currency());
    assertEquals(null, payload.transactionDate());

    JsonNode roundTripped = mapper.valueToTree(envelope);
    assertEquals(original, roundTripped);
  }
}
