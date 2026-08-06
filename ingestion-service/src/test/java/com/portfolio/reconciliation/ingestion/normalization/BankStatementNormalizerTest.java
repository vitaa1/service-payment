package com.portfolio.reconciliation.ingestion.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BankStatementNormalizerTest {

  private final BankStatementNormalizer normalizer = new BankStatementNormalizer();

  @Test
  void deveAplicarCurrencyDefaultBRLEUsarDescriptionComoCounterparty() {
    UUID ingestionId = UUID.randomUUID();
    BankStatementRequest req =
        new BankStatementRequest(
            "chg_9f8e7d6c5b4a",
            new BigDecimal("199.90"),
            LocalDate.parse("2026-07-29"),
            "PAGAMENTO LOJA EXEMPLO");

    TransactionNormalizedPayload payload = normalizer.normalize(ingestionId, req);

    assertEquals(Source.BANK_STATEMENT, payload.source());
    assertEquals("chg_9f8e7d6c5b4a", payload.externalReference());
    assertEquals(new BigDecimal("199.90"), payload.amount());
    assertEquals("BRL", payload.currency());
    assertEquals(LocalDate.parse("2026-07-29"), payload.transactionDate());
    assertEquals("PAGAMENTO LOJA EXEMPLO", payload.counterparty());
    assertNull(payload.sourceMetadata());
  }
}
