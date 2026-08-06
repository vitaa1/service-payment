package com.portfolio.reconciliation.ingestion.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalOrderNormalizerTest {

  private final InternalOrderNormalizer normalizer = new InternalOrderNormalizer();

  @Test
  void deveUsarExternalReferenceDiretoEPreservarOrderIdNoMetadata() {
    UUID ingestionId = UUID.randomUUID();
    InternalOrderRequest req =
        new InternalOrderRequest(
            "ORD-12345",
            "chg_9f8e7d6c5b4a",
            new BigDecimal("199.90"),
            "BRL",
            LocalDate.parse("2026-07-29"),
            "Loja Exemplo LTDA");

    TransactionNormalizedPayload payload = normalizer.normalize(ingestionId, req);

    assertEquals(Source.INTERNAL_ORDER, payload.source());
    assertEquals("chg_9f8e7d6c5b4a", payload.externalReference());
    assertEquals(new BigDecimal("199.90"), payload.amount());
    assertEquals("BRL", payload.currency());
    assertEquals(LocalDate.parse("2026-07-29"), payload.transactionDate());
    assertEquals("Loja Exemplo LTDA", payload.counterparty());
    assertEquals("ORD-12345", payload.sourceMetadata().get("orderId"));
  }
}
