package com.portfolio.reconciliation.events;

/**
 * Tipo de problema de um caso não-{@code MATCHED}. É {@link ReconciliationStatus} menos
 * {@code MATCHED} — um problema nunca é "casado". Ver CONTEXT.md.
 */
public enum DivergenceType {
  DIVERGENT,
  MISSING,
  DUPLICATE
}
