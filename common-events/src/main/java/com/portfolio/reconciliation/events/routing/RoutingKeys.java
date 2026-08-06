package com.portfolio.reconciliation.events.routing;

/** Routing keys publicadas em {@link Exchanges#EVENTS}. Ver docs/events/README.md. */
public final class RoutingKeys {

  public static final String TRANSACTION_NORMALIZED = "transaction.normalized";
  public static final String RECONCILIATION_COMPLETED = "reconciliation.completed";
  public static final String RECONCILIATION_DIVERGENCE_DETECTED =
      "reconciliation.divergence.detected";

  private RoutingKeys() {}
}
