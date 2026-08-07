package com.portfolio.reconciliation.notification.listener;

import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.notification.config.QueueNames;
import com.portfolio.reconciliation.notification.service.NotificationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome {@code DivergenceDetected} e delega ao {@link NotificationService}. Exceção não
 * tratada aciona o retry nativo do Spring AMQP; ao esgotar, cai na DLQ (ADR-0011).
 */
@Component
public class DivergenceDetectedListener {

  private final NotificationService notificationService;

  public DivergenceDetectedListener(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @RabbitListener(queues = QueueNames.DIVERGENCE_DETECTED)
  public void onMessage(EventEnvelope<DivergenceDetectedPayload> envelope) {
    notificationService.handle(envelope);
  }
}
