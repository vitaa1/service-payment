package com.portfolio.reconciliation.notification.config;

/**
 * Nomes de fila do notification-service. Não pertencem ao common-events — são infra do
 * consumidor (ADR-0005). O nome da fila também é usado como routing key do dead-letter
 * (ADR-0011) — evita cross-talk com a DLQ do report-service, que compartilha a mesma routing
 * key de produção no fan-out de DivergenceDetected. Ver docs/events/README.md.
 */
public final class QueueNames {

  public static final String DIVERGENCE_DETECTED = "notification.divergence-detected.q";
  public static final String DIVERGENCE_DETECTED_DLQ = "notification.divergence-detected.q.dlq";

  private QueueNames() {}
}
