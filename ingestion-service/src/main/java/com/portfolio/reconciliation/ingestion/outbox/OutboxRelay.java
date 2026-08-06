package com.portfolio.reconciliation.ingestion.outbox;

import com.portfolio.reconciliation.events.routing.Exchanges;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relay do Transactional Outbox (ADR-0006): lê as linhas pendentes com {@code SKIP LOCKED},
 * publica no RabbitMQ e marca {@code published_at} apenas após o confirm do broker. Um purge
 * agendado remove as publicadas antigas.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outboxRepository;
  private final RabbitTemplate rabbitTemplate;
  private final int maxAttempts;
  private final int batchSize;
  private final long confirmTimeoutMs;
  private final int retentionDays;

  public OutboxRelay(
      OutboxRepository outboxRepository,
      RabbitTemplate rabbitTemplate,
      @Value("${ingestion.outbox.relay.max-attempts}") int maxAttempts,
      @Value("${ingestion.outbox.relay.batch-size}") int batchSize,
      @Value("${ingestion.outbox.relay.confirm-timeout-ms}") long confirmTimeoutMs,
      @Value("${ingestion.outbox.purge.retention-days}") int retentionDays) {
    this.outboxRepository = outboxRepository;
    this.rabbitTemplate = rabbitTemplate;
    this.maxAttempts = maxAttempts;
    this.batchSize = batchSize;
    this.confirmTimeoutMs = confirmTimeoutMs;
    this.retentionDays = retentionDays;
  }

  @Scheduled(fixedDelayString = "${ingestion.outbox.relay.interval-ms}")
  @Transactional
  public void publishPending() {
    List<OutboxEvent> pending = outboxRepository.lockPending(maxAttempts, batchSize);
    for (OutboxEvent event : pending) {
      publishOne(event);
    }
  }

  private void publishOne(OutboxEvent event) {
    try {
      CorrelationData correlation = new CorrelationData(event.getEventId().toString());
      rabbitTemplate.send(Exchanges.EVENTS, event.getRoutingKey(), toMessage(event), correlation);
      CorrelationData.Confirm confirm =
          correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
      if (confirm != null && confirm.isAck()) {
        event.setPublishedAt(Instant.now());
      } else {
        registrarFalha(event, "broker nack");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      registrarFalha(event, "publicação interrompida");
    } catch (Exception ex) {
      registrarFalha(event, ex.getMessage());
    }
  }

  private void registrarFalha(OutboxEvent event, String motivo) {
    event.setAttempts(event.getAttempts() + 1);
    log.warn(
        "Falha ao publicar evento da outbox eventId={} (tentativa {}): {}",
        event.getEventId(),
        event.getAttempts(),
        motivo);
  }

  private Message toMessage(OutboxEvent event) {
    return MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        .setContentEncoding(StandardCharsets.UTF_8.name())
        .setMessageId(event.getEventId().toString())
        .build();
  }

  @Scheduled(fixedDelayString = "${ingestion.outbox.purge.interval-ms}")
  @Transactional
  public void purgePublished() {
    Instant threshold = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    int removed = outboxRepository.deletePublishedBefore(threshold);
    if (removed > 0) {
      log.info("Purge da outbox: {} linha(s) publicada(s) removida(s)", removed);
    }
  }
}
