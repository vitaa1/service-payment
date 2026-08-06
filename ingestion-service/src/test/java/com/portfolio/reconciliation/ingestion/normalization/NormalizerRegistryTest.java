package com.portfolio.reconciliation.ingestion.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.portfolio.reconciliation.events.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

class NormalizerRegistryTest {

  private final GatewayNormalizer gateway = new GatewayNormalizer();
  private final BankStatementNormalizer bank = new BankStatementNormalizer();
  private final InternalOrderNormalizer order = new InternalOrderNormalizer();
  private final NormalizerRegistry registry =
      new NormalizerRegistry(List.of(gateway, bank, order));

  @Test
  void deveSelecionarONormalizerPelaFonte() {
    assertSame(gateway, registry.forSource(Source.GATEWAY));
    assertSame(bank, registry.forSource(Source.BANK_STATEMENT));
    assertSame(order, registry.forSource(Source.INTERNAL_ORDER));
  }

  @Test
  void cadaNormalizerDeveExporSeuTipoDeRequest() {
    assertEquals(GatewayRequest.class, gateway.requestType());
    assertEquals(BankStatementRequest.class, bank.requestType());
    assertEquals(InternalOrderRequest.class, order.requestType());
  }
}
