package com.portfolio.reconciliation.reconciliation.domain;

import com.portfolio.reconciliation.events.ReconciliationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * O grupo avaliado, uma linha por matching key (= externalReference, ADR-0009). O {@code version}
 * cresce a cada reavaliação e serve de ordenação nos consumidores (ADR-0010). Ver
 * docs/architecture.md §6.2.
 */
@Entity
@Table(name = "reconciliation_case")
public class ReconciliationCase {

  @Id private UUID id;

  @Column(name = "matching_key")
  private String matchingKey;

  @Enumerated(EnumType.STRING)
  private ReconciliationStatus status;

  private int version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "divergence_details")
  private String divergenceDetails;

  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", insertable = false)
  private Instant updatedAt;

  protected ReconciliationCase() {
    // JPA
  }

  public ReconciliationCase(UUID id, String matchingKey, ReconciliationStatus status) {
    this.id = id;
    this.matchingKey = matchingKey;
    this.status = status;
    this.version = 1;
  }

  public UUID getId() {
    return id;
  }

  public String getMatchingKey() {
    return matchingKey;
  }

  public ReconciliationStatus getStatus() {
    return status;
  }

  public void setStatus(ReconciliationStatus status) {
    this.status = status;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public String getDivergenceDetails() {
    return divergenceDetails;
  }

  public void setDivergenceDetails(String divergenceDetails) {
    this.divergenceDetails = divergenceDetails;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
