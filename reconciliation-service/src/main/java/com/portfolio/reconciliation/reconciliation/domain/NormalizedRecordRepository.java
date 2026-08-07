package com.portfolio.reconciliation.reconciliation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NormalizedRecordRepository extends JpaRepository<NormalizedRecord, UUID> {

  /** Dedup de TransactionNormalized por eventId (ADR-0002). */
  Optional<NormalizedRecord> findByEventId(UUID eventId);

  /** Todas as pernas de um caso — base da reavaliação. */
  List<NormalizedRecord> findByCaseId(UUID caseId);
}
