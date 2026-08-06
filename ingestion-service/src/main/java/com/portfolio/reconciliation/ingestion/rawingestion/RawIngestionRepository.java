package com.portfolio.reconciliation.ingestion.rawingestion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawIngestionRepository extends JpaRepository<RawIngestion, UUID> {

  /** Suporte à idempotência (ADR-0008): reentrega com a mesma key devolve o registro existente. */
  Optional<RawIngestion> findByIdempotencyKey(String idempotencyKey);
}
