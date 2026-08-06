package com.portfolio.reconciliation.events.routing;

/** Valores de {@code eventType} do envelope. Ver docs/events/README.md. */
public final class EventTypes {

  public static final String TRANSACTION_NORMALIZED = "TransactionNormalized";
  public static final String RECONCILIATION_COMPLETED = "ReconciliationCompleted";
  public static final String DIVERGENCE_DETECTED = "DivergenceDetected";

  private EventTypes() {}
}
