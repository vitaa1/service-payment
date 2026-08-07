package com.portfolio.reconciliation.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import com.portfolio.reconciliation.events.routing.EventTypes;
import com.portfolio.reconciliation.events.routing.Exchanges;
import com.portfolio.reconciliation.events.routing.RoutingKeys;
import com.portfolio.reconciliation.notification.config.QueueNames;
import com.portfolio.reconciliation.notification.domain.NotificationLog;
import com.portfolio.reconciliation.notification.domain.NotificationLogRepository;
import com.portfolio.reconciliation.notification.domain.NotificationStatus;
import com.portfolio.reconciliation.notification.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;

/**
 * Fatia de ponta a ponta: publica DivergenceDetected na exchange como o reconciliation faria, o
 * listener consome e o NotificationService reage. Cobre dedup, falha de envio esgotando o
 * retry (DLQ) e mensagem poison (DLQ).
 *
 * <p>{@code management.health.mail.enabled=false}: com {@code @MockBean JavaMailSender}, o
 * {@code MailHealthContributorAutoConfiguration} do Actuator falha ao subir o contexto
 * ("'beans' must not be empty") — o mock não populam o {@code Map<String, MailSender>} que o
 * health contributor espera. Sem relação com a lógica testada aqui, então desligamos só o
 * indicador de saúde do mail nesta classe.
 */
@TestPropertySource(properties = "management.health.mail.enabled=false")
class NotificationEndToEndIT extends AbstractIntegrationTest {

  @Autowired RabbitTemplate rabbitTemplate;
  @Autowired ObjectMapper objectMapper;
  @Autowired NotificationLogRepository repository;
  @MockBean JavaMailSender mailSender;

  @BeforeEach
  void limpa() {
    repository.deleteAll();
    reset(mailSender);
    drain(QueueNames.DIVERGENCE_DETECTED_DLQ);
  }

  private void drain(String queue) {
    while (rabbitTemplate.receive(queue) != null) {
      // esvazia
    }
  }

  @Test
  void publicaNaExchangeEhConsumidoEGravaSent() throws Exception {
    UUID eventId = UUID.randomUUID();
    publish(envelope(eventId, "chg_" + eventId));

    NotificationLog log = awaitLog(eventId);
    assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
  }

  @Test
  void reentregaDoMesmoEventIdJaEnviadoNaoReenviaEmail() throws Exception {
    UUID eventId = UUID.randomUUID();
    EventEnvelope<DivergenceDetectedPayload> envelope = envelope(eventId, "chg_" + eventId);
    publish(envelope);
    awaitLog(eventId);
    reset(mailSender); // limpa a contagem da 1a entrega

    publish(envelope); // reentrega manual do mesmo eventId

    Thread.sleep(1000); // dá tempo do listener processar, se fosse processar
    org.mockito.Mockito.verifyNoInteractions(mailSender);
  }

  @Test
  void falhaDeEnvioEsgotaRetryECaiNaDlq() throws Exception {
    doThrow(new MailSendException("smtp indisponível")).when(mailSender).send(any(SimpleMailMessage.class));
    UUID eventId = UUID.randomUUID();
    publish(envelope(eventId, "chg_" + eventId));

    Message dead = rabbitTemplate.receive(QueueNames.DIVERGENCE_DETECTED_DLQ, 15000);
    assertNotNull(dead, "mensagem deve cair na DLQ após esgotar o retry de envio");

    NotificationLog log = repository.findByEventId(eventId).orElseThrow();
    assertThat(log.getStatus()).isEqualTo(NotificationStatus.FAILED);
  }

  @Test
  void mensagemInvalidaEsgotaRetryECaiNaDlqSemLog() {
    Message poison =
        MessageBuilder.withBody("{ isto nao eh json valido".getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    rabbitTemplate.send(Exchanges.EVENTS, RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED, poison);

    Message dead = rabbitTemplate.receive(QueueNames.DIVERGENCE_DETECTED_DLQ, 10000);
    assertNotNull(dead, "mensagem inválida deve cair na DLQ após esgotar o retry");
    assertThat(repository.count()).isZero();
  }

  private void publish(EventEnvelope<DivergenceDetectedPayload> envelope) throws Exception {
    String json = objectMapper.writeValueAsString(envelope);
    Message message =
        MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    rabbitTemplate.send(Exchanges.EVENTS, RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED, message);
  }

  private EventEnvelope<DivergenceDetectedPayload> envelope(UUID eventId, String matchingKey) {
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.randomUUID(),
            matchingKey,
            DivergenceType.MISSING,
            1,
            matchingKey,
            new MissingDetails(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), List.of(Source.INTERNAL_ORDER)),
            Instant.now());
    return new EventEnvelope<>(
        eventId, EventTypes.DIVERGENCE_DETECTED, 1, Instant.now(), UUID.randomUUID(),
        "reconciliation-service", payload);
  }

  /** Polling simples — o consumo é assíncrono, sem framework extra de espera no projeto. */
  private NotificationLog awaitLog(UUID eventId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      Optional<NotificationLog> found = repository.findByEventId(eventId);
      if (found.isPresent()) {
        return found.get();
      }
      Thread.sleep(200);
    }
    fail("notification_log para eventId " + eventId + " não apareceu a tempo");
    return null;
  }
}
