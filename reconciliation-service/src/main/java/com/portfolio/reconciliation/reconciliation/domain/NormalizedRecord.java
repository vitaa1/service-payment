package com.portfolio.reconciliation.reconciliation.domain;

import com.portfolio.reconciliation.events.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma perna canônica recebida (um registro de uma fonte). Dedup por {@code event_id}. O vínculo
 * com o caso é o {@code case_id}; o {@code external_reference} é a matching key (ADR-0009).
 */
@Entity
@Table(name = "normalized_record")
public class NormalizedRecord {

  @Id private UUID id;

  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "case_id")
  private UUID caseId;

  @Enumerated(EnumType.STRING)
  private Source source;

  @Column(name = "external_reference")
  private String externalReference;

  private BigDecimal amount;

  private String currency;

  @Column(name = "transaction_date")
  private LocalDate transactionDate;

  @Column(name = "trace_id")
  private UUID traceId;

  @Column(name = "received_at", insertable = false, updatable = false)
  private Instant receivedAt;

  protected NormalizedRecord() {
    // JPA
  }

  public NormalizedRecord(
      UUID id,
      UUID eventId,
      UUID caseId,
      Source source,
      String externalReference,
      BigDecimal amount,
      String currency,
      LocalDate transactionDate,
      UUID traceId) {
    this.id = id;
    this.eventId = eventId;
    this.caseId = caseId;
    this.source = source;
    this.externalReference = externalReference;
    this.amount = amount;
    this.currency = currency;
    this.transactionDate = transactionDate;
    this.traceId = traceId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public Source getSource() {
    return source;
  }

  public String getExternalReference() {
    return externalReference;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public LocalDate getTransactionDate() {
    return transactionDate;
  }

  public UUID getTraceId() {
    return traceId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}
