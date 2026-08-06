package com.portfolio.reconciliation.events.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.reconciliation.events.Source;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionNormalizedPayloadTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void deveSerializarAmountComoStringDecimal() throws Exception {
    TransactionNormalizedPayload payload =
        new TransactionNormalizedPayload(
            UUID.fromString("8a2b1c3d-4e5f-6071-8293-a4b5c6d7e8f9"),
            Source.GATEWAY,
            "chg_9f8e7d6c5b4a",
            new BigDecimal("199.90"),
            "BRL",
            LocalDate.parse("2026-07-29"),
            "Loja Exemplo LTDA",
            null);

    ObjectNode json = (ObjectNode) mapper.valueToTree(payload);

    assertEquals("199.90", json.get("amount").asText());
    assertEquals(true, json.get("amount").isTextual());
  }
}
