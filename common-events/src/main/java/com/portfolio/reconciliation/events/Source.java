package com.portfolio.reconciliation.events;

/** Origem do dado de uma transação. Ver docs/events/ e CONTEXT.md. */
public enum Source {
  GATEWAY,
  BANK_STATEMENT,
  INTERNAL_ORDER
}
