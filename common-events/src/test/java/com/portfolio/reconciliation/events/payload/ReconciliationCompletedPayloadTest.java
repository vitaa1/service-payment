package com.portfolio.reconciliation.events.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationCompletedPayloadTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void deveSerializarStatusAmountESourcesPresentCorretamente() throws Exception {
    ReconciliationCompletedPayload payload =
        new ReconciliationCompletedPayload(
            UUID.fromString("d1e2f3a4-b5c6-4a7e-8f90-1a2b3c4d5e6f"),
            "chg_9f8e7d6c5b4a",
            ReconciliationStatus.MATCHED,
            3,
            "chg_9f8e7d6c5b4a",
            new BigDecimal("199.90"),
            "BRL",
            LocalDate.parse("2026-07-29"),
            List.of(Source.GATEWAY, Source.BANK_STATEMENT, Source.INTERNAL_ORDER),
            Instant.parse("2026-07-29T13:45:15.001Z"));

    ObjectNode json = (ObjectNode) mapper.valueToTree(payload);

    assertEquals("MATCHED", json.get("status").asText());
    assertEquals(true, json.get("amount").isTextual());
    assertEquals("199.90", json.get("amount").asText());
    assertEquals(3, json.get("sourcesPresent").size());
    assertEquals("GATEWAY", json.get("sourcesPresent").get(0).asText());
    assertEquals("2026-07-29T13:45:15.001Z", json.get("evaluatedAt").asText());
  }
}
