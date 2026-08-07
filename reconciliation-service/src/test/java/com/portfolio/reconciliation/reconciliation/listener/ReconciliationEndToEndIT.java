package com.portfolio.reconciliation.reconciliation.listener;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import com.portfolio.reconciliation.events.routing.Exchanges;
import com.portfolio.reconciliation.events.routing.EventTypes;
import com.portfolio.reconciliation.events.routing.RoutingKeys;
import com.portfolio.reconciliation.reconciliation.config.QueueNames;
import com.portfolio.reconciliation.reconciliation.domain.NormalizedRecordRepository;
import com.portfolio.reconciliation.reconciliation.domain.ReconciliationCase;
import com.portfolio.reconciliation.reconciliation.domain.ReconciliationCaseRepository;
import com.portfolio.reconciliation.reconciliation.outbox.OutboxRelay;
import com.portfolio.reconciliation.reconciliation.outbox.OutboxRepository;
import com.portfolio.reconciliation.reconciliation.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Fatia 4 de ponta a ponta: publica na exchange como o ingestion faria, o listener consome e
 * reavalia, o relay publica os eventos resultantes. Cobre também o caminho de poison message
 * (retry → DLQ).
 */
class ReconciliationEndToEndIT extends AbstractIntegrationTest {

  private static final String COMPLETED_QUEUE = "test.reconciliation-completed.q";
  private static final String DIVERGENCE_QUEUE = "test.divergence-detected.q";

  @TestConfiguration
  static class TestTopology {
    @Bean
    Queue completedQueue() {
      return new Queue(COMPLETED_QUEUE, false);
    }

    @Bean
    Queue divergenceQueue() {
      return new Queue(DIVERGENCE_QUEUE, false);
    }

    @Bean
    Binding completedBinding(Queue completedQueue, TopicExchange paymentsEventsExchange) {
      return BindingBuilder.bind(completedQueue)
          .to(paymentsEventsExchange)
          .with(RoutingKeys.RECONCILIATION_COMPLETED);
    }

    @Bean
    Binding divergenceBinding(Queue divergenceQueue, TopicExchange paymentsEventsExchange) {
      return BindingBuilder.bind(divergenceQueue)
          .to(paymentsEventsExchange)
          .with(RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED);
    }
  }

  @Autowired RabbitTemplate rabbitTemplate;
  @Autowired ObjectMapper objectMapper;
  @Autowired OutboxRelay relay;
  @Autowired ReconciliationCaseRepository caseRepository;
  @Autowired NormalizedRecordRepository recordRepository;
  @Autowired OutboxRepository outboxRepository;

  @BeforeEach
  void limpa() {
    outboxRepository.deleteAll();
    recordRepository.deleteAll();
    caseRepository.deleteAll();
    drain(COMPLETED_QUEUE);
    drain(DIVERGENCE_QUEUE);
    drain(QueueNames.TRANSACTION_NORMALIZED_DLQ);
  }

  private void drain(String queue) {
    while (rabbitTemplate.receive(queue) != null) {
      // esvazia
    }
  }

  @Test
  void publicaNaExchangeEhConsumidoEReleiEmiteCompletedEDivergence() throws Exception {
    String ref = "chg_" + UUID.randomUUID();
    publish(envelope(Source.GATEWAY, ref, "199.90", "BRL", LocalDate.parse("2026-07-29")));

    awaitCaseComStatus(ref, ReconciliationStatus.MISSING);
    relay.publishPending();

    assertNotNull(rabbitTemplate.receive(COMPLETED_QUEUE, 5000), "ReconciliationCompleted deve chegar");
    assertNotNull(rabbitTemplate.receive(DIVERGENCE_QUEUE, 5000), "DivergenceDetected (MISSING) deve chegar");
  }

  @Test
  void mensagemInvalidaEsgotaRetryECaiNaDlq() {
    Message poison =
        MessageBuilder.withBody("{ isto nao eh json valido".getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    rabbitTemplate.send(Exchanges.EVENTS, RoutingKeys.TRANSACTION_NORMALIZED, poison);

    Message dead = rabbitTemplate.receive(QueueNames.TRANSACTION_NORMALIZED_DLQ, 10000);
    assertNotNull(dead, "mensagem inválida deve cair na DLQ após esgotar o retry");
  }

  private void publish(EventEnvelope<TransactionNormalizedPayload> envelope) throws Exception {
    String json = objectMapper.writeValueAsString(envelope);
    Message message =
        MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    rabbitTemplate.send(Exchanges.EVENTS, RoutingKeys.TRANSACTION_NORMALIZED, message);
  }

  private EventEnvelope<TransactionNormalizedPayload> envelope(
      Source source, String ref, String amount, String currency, LocalDate date) {
    TransactionNormalizedPayload payload =
        new TransactionNormalizedPayload(
            UUID.randomUUID(), source, ref, new BigDecimal(amount), currency, date, null, null);
    return new EventEnvelope<>(
        UUID.randomUUID(),
        EventTypes.TRANSACTION_NORMALIZED,
        1,
        Instant.now(),
        UUID.randomUUID(),
        "ingestion-service",
        payload);
  }

  /** Polling simples — o consumo é assíncrono, sem framework extra de espera no projeto. */
  private ReconciliationCase awaitCaseComStatus(String matchingKey, ReconciliationStatus expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      Optional<ReconciliationCase> found = caseRepository.findByMatchingKey(matchingKey);
      if (found.isPresent() && found.get().getStatus() == expected) {
        return found.get();
      }
      Thread.sleep(200);
    }
    fail("caso '" + matchingKey + "' não atingiu o status " + expected + " a tempo");
    return null;
  }
}
