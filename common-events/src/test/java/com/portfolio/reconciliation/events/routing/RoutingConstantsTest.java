package com.portfolio.reconciliation.events.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Trava os literais contra docs/events/README.md (fonte da verdade da topologia). */
class RoutingConstantsTest {

  @Test
  void exchangesDevemBaterComOsDocs() {
    assertEquals("payments.events", Exchanges.EVENTS);
    assertEquals("payments.events.dlx", Exchanges.EVENTS_DLX);
  }

  @Test
  void routingKeysDevemBaterComOsDocs() {
    assertEquals("transaction.normalized", RoutingKeys.TRANSACTION_NORMALIZED);
    assertEquals("reconciliation.completed", RoutingKeys.RECONCILIATION_COMPLETED);
    assertEquals(
        "reconciliation.divergence.detected", RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED);
  }

  @Test
  void eventTypesDevemBaterComOsDocs() {
    assertEquals("TransactionNormalized", EventTypes.TRANSACTION_NORMALIZED);
    assertEquals("ReconciliationCompleted", EventTypes.RECONCILIATION_COMPLETED);
    assertEquals("DivergenceDetected", EventTypes.DIVERGENCE_DETECTED);
  }
}
