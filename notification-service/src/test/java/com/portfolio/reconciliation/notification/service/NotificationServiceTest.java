package com.portfolio.reconciliation.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import com.portfolio.reconciliation.notification.config.NotificationProperties;
import com.portfolio.reconciliation.notification.domain.NotificationLog;
import com.portfolio.reconciliation.notification.domain.NotificationLogRepository;
import com.portfolio.reconciliation.notification.domain.NotificationStatus;
import com.portfolio.reconciliation.notification.email.EmailComposer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;

class NotificationServiceTest {

  private NotificationLogRepository repository;
  private JavaMailSender mailSender;
  private NotificationService service;

  @BeforeEach
  void setUp() {
    repository = mock(NotificationLogRepository.class);
    mailSender = mock(JavaMailSender.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    NotificationProperties properties =
        new NotificationProperties("no-reply@reconciliation.local", "ops@reconciliation.local");
    service =
        new NotificationService(
            repository, mailSender, new EmailComposer(), properties, new ObjectMapper(),
            transactionManager);
  }

  private EventEnvelope<DivergenceDetectedPayload> envelope(UUID eventId) {
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.randomUUID(),
            "chg_1",
            DivergenceType.MISSING,
            2,
            "chg_1",
            new MissingDetails(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), List.of(Source.INTERNAL_ORDER)),
            Instant.parse("2026-08-07T12:00:00Z"));
    return new EventEnvelope<>(
        eventId, "DivergenceDetected", 1, Instant.now(), UUID.randomUUID(),
        "reconciliation-service", payload);
  }

  @Test
  void eventoNovoEnviaEGravaSent() {
    UUID eventId = UUID.randomUUID();
    when(repository.findByEventId(eventId)).thenReturn(Optional.empty());

    service.handle(envelope(eventId));

    verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
  }

  @Test
  void eventoJaEnviadoEhIgnorado() {
    UUID eventId = UUID.randomUUID();
    NotificationLog sent =
        new NotificationLog(
            UUID.randomUUID(), eventId, UUID.randomUUID(), "EMAIL", "ops@reconciliation.local",
            UUID.randomUUID());
    sent.setStatus(NotificationStatus.SENT);
    when(repository.findByEventId(eventId)).thenReturn(Optional.of(sent));

    service.handle(envelope(eventId));

    verify(mailSender, never()).send(any(SimpleMailMessage.class));
    verify(repository, never()).save(any());
  }

  @Test
  void falhaDeEnvioGravaFailedERelanca() {
    UUID eventId = UUID.randomUUID();
    when(repository.findByEventId(eventId)).thenReturn(Optional.empty());
    doThrow(new MailSendException("smtp indisponível")).when(mailSender).send(any(SimpleMailMessage.class));

    assertThrows(MailSendException.class, () -> service.handle(envelope(eventId)));

    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
  }

  @Test
  void reenvioAposFalhaAtualizaParaSent() {
    UUID eventId = UUID.randomUUID();
    NotificationLog failed =
        new NotificationLog(
            UUID.randomUUID(), eventId, UUID.randomUUID(), "EMAIL", "ops@reconciliation.local",
            UUID.randomUUID());
    failed.setStatus(NotificationStatus.FAILED);
    when(repository.findByEventId(eventId)).thenReturn(Optional.of(failed));

    service.handle(envelope(eventId));

    verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(captor.getValue().getId()).isEqualTo(failed.getId());
  }
}
