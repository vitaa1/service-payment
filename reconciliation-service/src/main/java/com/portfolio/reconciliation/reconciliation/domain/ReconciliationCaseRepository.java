package com.portfolio.reconciliation.reconciliation.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationCaseRepository extends JpaRepository<ReconciliationCase, UUID> {

  Optional<ReconciliationCase> findByMatchingKey(String matchingKey);

  /**
   * Trava a linha do caso (`SELECT ... FOR UPDATE`) para serializar as reavaliações do mesmo caso
   * (ADR-0010). Usado dentro da transação de reavaliação.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ReconciliationCase c where c.matchingKey = :matchingKey")
  Optional<ReconciliationCase> lockByMatchingKey(@Param("matchingKey") String matchingKey);
}
