package com.portfolio.reconciliation.reconciliation.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

  /**
   * Trava as linhas pendentes para o relay, pulando as já travadas por outra instância
   * ({@code SKIP LOCKED}), em ordem de inserção. Deve rodar dentro de uma transação (o lock vale
   * até o commit). Ver ADR-0006.
   */
  @Query(
      value =
          "SELECT * FROM outbox WHERE published_at IS NULL AND attempts < :maxAttempts "
              + "ORDER BY id FOR UPDATE SKIP LOCKED LIMIT :batchSize",
      nativeQuery = true)
  List<OutboxEvent> lockPending(
      @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

  /** Purge: remove linhas já publicadas anteriores ao limite de retenção. */
  @Modifying
  @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :threshold")
  int deletePublishedBefore(@Param("threshold") Instant threshold);
}
