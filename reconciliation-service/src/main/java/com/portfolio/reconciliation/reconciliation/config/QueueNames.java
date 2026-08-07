package com.portfolio.reconciliation.reconciliation.config;

/**
 * Nomes de fila do reconciliation-service. Não pertencem ao common-events — são infra do
 * consumidor (ADR-0005). Ver docs/events/README.md (tabela de bindings).
 */
public final class QueueNames {

  public static final String TRANSACTION_NORMALIZED = "reconciliation.transaction-normalized.q";
  public static final String TRANSACTION_NORMALIZED_DLQ =
      "reconciliation.transaction-normalized.q.dlq";

  private QueueNames() {}
}
