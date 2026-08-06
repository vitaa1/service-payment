package com.portfolio.reconciliation.ingestion.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayNormalizerTest {

  private final GatewayNormalizer normalizer = new GatewayNormalizer();

  @Test
  void deveConverterCentavosParaDecimalEExtrairDataDePaidAt() {
    UUID ingestionId = UUID.randomUUID();
    GatewayRequest req =
        new GatewayRequest(
            "chg_9f8e7d6c5b4a",
            "txn_abc123",
            19990L,
            "BRL",
            Instant.parse("2026-07-29T13:45:00Z"),
            "Loja Exemplo LTDA",
            "credit_card");

    TransactionNormalizedPayload payload = normalizer.normalize(ingestionId, req);

    assertEquals(ingestionId, payload.ingestionId());
    assertEquals(Source.GATEWAY, payload.source());
    assertEquals("chg_9f8e7d6c5b4a", payload.externalReference());
    assertEquals(new BigDecimal("199.90"), payload.amount());
    assertEquals("BRL", payload.currency());
    assertEquals(LocalDate.parse("2026-07-29"), payload.transactionDate());
    assertEquals("Loja Exemplo LTDA", payload.counterparty());
    assertEquals("txn_abc123", payload.sourceMetadata().get("gatewayTxnId"));
    assertEquals("credit_card", payload.sourceMetadata().get("paymentMethod"));
  }

  @Test
  void deveReportarGatewayComoSuaFonte() {
    assertEquals(Source.GATEWAY, normalizer.source());
  }
}
