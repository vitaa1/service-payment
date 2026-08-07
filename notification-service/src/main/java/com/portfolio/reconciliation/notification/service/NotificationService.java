package com.portfolio.reconciliation.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.notification.config.NotificationProperties;
import com.portfolio.reconciliation.notification.domain.NotificationLog;
import com.portfolio.reconciliation.notification.domain.NotificationLogRepository;
import com.portfolio.reconciliation.notification.domain.NotificationStatus;
import com.portfolio.reconciliation.notification.email.EmailComposer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orquestra a notificação de um {@code DivergenceDetected}: dedup por {@code eventId},
 * check-then-send-then-log (ADR-0011). {@code MailException} grava {@code FAILED} e relança —
 * quem aciona o retry é o listener (retry nativo do Spring AMQP).
 */
@Service
public class NotificationService {

  private static final String CHANNEL_EMAIL = "EMAIL";

  private final NotificationLogRepository repository;
  private final JavaMailSender mailSender;
  private final EmailComposer emailComposer;
  private final NotificationProperties properties;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;

  public NotificationService(
      NotificationLogRepository repository,
      JavaMailSender mailSender,
      EmailComposer emailComposer,
      NotificationProperties properties,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.mailSender = mailSender;
    this.emailComposer = emailComposer;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void handle(EventEnvelope<DivergenceDetectedPayload> envelope) {
    UUID eventId = envelope.eventId();
    Optional<NotificationLog> existing = repository.findByEventId(eventId);
    if (existing.map(NotificationLog::getStatus).orElse(null) == NotificationStatus.SENT) {
      return; // reentrega já notificada — idempotência por eventId (ADR-0011)
    }

    DivergenceDetectedPayload payload = envelope.payload();
    EmailComposer.EmailContent content = emailComposer.compose(envelope);
    String summary = toJson(payload, content.subject());

    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(properties.from());
    message.setTo(properties.to());
    message.setSubject(content.subject());
    message.setText(content.body());

    try {
      mailSender.send(message);
    } catch (MailException e) {
      try {
        persist(eventId, existing, payload.caseId(), envelope.traceId(), NotificationStatus.FAILED, summary);
      } catch (RuntimeException persistFailure) {
        // Preserva a causa raiz (falha de envio) mesmo se a gravação do FAILED também falhar
        // (ex.: banco indisponível) — o retry do listener ainda dispara por qualquer uma delas.
        persistFailure.addSuppressed(e);
        throw persistFailure;
      }
      throw e;
    }
    persist(eventId, existing, payload.caseId(), envelope.traceId(), NotificationStatus.SENT, summary);
  }

  private void persist(
      UUID eventId,
      Optional<NotificationLog> existing,
      UUID caseId,
      UUID traceId,
      NotificationStatus status,
      String summary) {
    transactionTemplate.executeWithoutResult(
        tx -> {
          NotificationLog log =
              existing.orElseGet(
                  () ->
                      new NotificationLog(
                          UUID.randomUUID(), eventId, caseId, CHANNEL_EMAIL, properties.to(), traceId));
          log.setStatus(status);
          log.setPayloadSummary(summary);
          log.setSentAt(Instant.now());
          repository.save(log);
        });
  }

  private record PayloadSummary(
      UUID caseId, String matchingKey, DivergenceType divergenceType, String externalReference, String subject) {}

  private String toJson(DivergenceDetectedPayload payload, String subject) {
    try {
      return objectMapper.writeValueAsString(
          new PayloadSummary(
              payload.caseId(), payload.matchingKey(), payload.divergenceType(),
              payload.externalReference(), subject));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("falha ao serializar payload_summary", e);
    }
  }
}
