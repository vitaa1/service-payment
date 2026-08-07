package com.portfolio.reconciliation.notification.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

  /** Dedup de DivergenceDetected por eventId (ADR-0011). */
  Optional<NotificationLog> findByEventId(UUID eventId);
}
