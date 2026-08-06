package com.portfolio.reconciliation.ingestion.outbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portfolio.reconciliation.events.routing.RoutingKeys;
import com.portfolio.reconciliation.ingestion.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Publica no RabbitMQ real (Testcontainers) e marca published_at após o ack do broker. */
class OutboxRelayIT extends AbstractIntegrationTest {

  private static final String TEST_QUEUE = "test.transaction-normalized.q";

  @TestConfiguration
  static class TestTopology {
    @Bean
    Queue testQueue() {
      return new Queue(TEST_QUEUE, false);
    }

    @Bean
    Binding testBinding(Queue testQueue, TopicExchange paymentsEventsExchange) {
      return BindingBuilder.bind(testQueue)
          .to(paymentsEventsExchange)
          .with(RoutingKeys.TRANSACTION_NORMALIZED);
    }
  }

  @Autowired OutboxRelay relay;
  @Autowired OutboxRepository outboxRepository;
  @Autowired RabbitTemplate rabbitTemplate;

  @BeforeEach
  void limpa() {
    outboxRepository.deleteAll();
    while (rabbitTemplate.receive(TEST_QUEUE) != null) {
      // drena a fila de teste
    }
  }

  private OutboxEvent novoEvento(UUID eventId) {
    return new OutboxEvent(
        eventId,
        "TransactionNormalized",
        RoutingKeys.TRANSACTION_NORMALIZED,
        UUID.randomUUID(),
        "{\"eventId\":\"" + eventId + "\",\"eventType\":\"TransactionNormalized\"}");
  }

  @Test
  void devePublicarPendenteMarcarPublishedAtEEntregarNaFila() {
    UUID eventId = UUID.randomUUID();
    OutboxEvent event = outboxRepository.saveAndFlush(novoEvento(eventId));

    relay.publishPending();

    Message received = rabbitTemplate.receive(TEST_QUEUE, 5000);
    assertNotNull(received, "a mensagem deve chegar na fila ligada à exchange");
    assertTrue(new String(received.getBody(), UTF_8).contains(eventId.toString()));
    assertEquals(
        MessageProperties.CONTENT_TYPE_JSON, received.getMessageProperties().getContentType());

    OutboxEvent reloaded = outboxRepository.findById(event.getId()).orElseThrow();
    assertNotNull(reloaded.getPublishedAt(), "published_at deve ser marcado após o confirm do broker");
  }

  @Test
  void naoDeveRepublicarUmEventoJaPublicado() {
    outboxRepository.saveAndFlush(novoEvento(UUID.randomUUID()));
    relay.publishPending();
    assertNotNull(rabbitTemplate.receive(TEST_QUEUE, 5000));

    relay.publishPending();

    assertNull(rabbitTemplate.receive(TEST_QUEUE, 1000), "não deve republicar o que já foi publicado");
  }

  @Test
  void purgeDeveApagarPublicadasAntigasMasManterRecentes() {
    OutboxEvent antiga = outboxRepository.saveAndFlush(novoEvento(UUID.randomUUID()));
    antiga.setPublishedAt(Instant.now().minus(30, ChronoUnit.DAYS));
    outboxRepository.saveAndFlush(antiga);

    OutboxEvent recente = outboxRepository.saveAndFlush(novoEvento(UUID.randomUUID()));
    recente.setPublishedAt(Instant.now());
    outboxRepository.saveAndFlush(recente);

    relay.purgePublished();

    assertTrue(outboxRepository.findById(antiga.getId()).isEmpty(), "antiga publicada deve ser purgada");
    assertTrue(outboxRepository.findById(recente.getId()).isPresent(), "recente deve permanecer");
  }
}
