package com.portfolio.reconciliation.events;

/** Estado atual de um caso de conciliação. Ver docs/events/ e CONTEXT.md. */
public enum ReconciliationStatus {
  MATCHED,
  DIVERGENT,
  MISSING,
  DUPLICATE
}
