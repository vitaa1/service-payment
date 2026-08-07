package com.portfolio.reconciliation.reconciliation.listener;

import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import com.portfolio.reconciliation.reconciliation.config.QueueNames;
import com.portfolio.reconciliation.reconciliation.service.ReconciliationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome {@code TransactionNormalized} e delega ao {@link ReconciliationService}. O tipo
 * concreto do parâmetro resolve a desserialização (Decisão 2/ADR-0005) — esta fila só
 * carrega este evento. Exceção não tratada aciona o retry nativo do Spring AMQP; ao
 * esgotar, cai na DLQ (ADR-0010, decisão 6).
 */
@Component
public class TransactionNormalizedListener {

  private final ReconciliationService reconciliationService;

  public TransactionNormalizedListener(ReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  @RabbitListener(queues = QueueNames.TRANSACTION_NORMALIZED)
  public void onMessage(EventEnvelope<TransactionNormalizedPayload> envelope) {
    reconciliationService.handle(envelope);
  }
}
