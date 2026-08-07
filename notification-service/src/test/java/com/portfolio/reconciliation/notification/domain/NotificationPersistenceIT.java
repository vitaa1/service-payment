package com.portfolio.reconciliation.notification.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portfolio.reconciliation.notification.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Fatia de persistência: migration Flyway sobe o schema e a entidade persiste/recupera. */
class NotificationPersistenceIT extends AbstractIntegrationTest {

  @Autowired NotificationLogRepository repository;

  @Test
  void devePersistirERecuperarPorEventId() {
    UUID eventId = UUID.randomUUID();
    NotificationLog log =
        new NotificationLog(
            UUID.randomUUID(), eventId, UUID.randomUUID(), "EMAIL", "ops@reconciliation.local",
            UUID.randomUUID());
    log.setStatus(NotificationStatus.SENT);
    log.setPayloadSummary("{\"subject\":\"teste\"}");
    log.setSentAt(Instant.now());

    repository.saveAndFlush(log);

    NotificationLog loaded = repository.findByEventId(eventId).orElseThrow();
    assertEquals(NotificationStatus.SENT, loaded.getStatus());
    assertEquals("EMAIL", loaded.getChannel());
    assertNotNull(loaded.getSentAt());
    assertTrue(repository.findByEventId(UUID.randomUUID()).isEmpty());
  }

  @Test
  void deveImporUniqueNoEventId() {
    UUID eventId = UUID.randomUUID();
    NotificationLog first = novoLog(eventId);
    repository.saveAndFlush(first);

    NotificationLog duplicate = novoLog(eventId);
    assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(duplicate));
  }

  private NotificationLog novoLog(UUID eventId) {
    NotificationLog log =
        new NotificationLog(
            UUID.randomUUID(), eventId, UUID.randomUUID(), "EMAIL", "ops@reconciliation.local",
            UUID.randomUUID());
    log.setStatus(NotificationStatus.FAILED);
    log.setPayloadSummary("{}");
    log.setSentAt(Instant.now());
    return log;
  }
}
