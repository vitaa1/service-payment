package com.portfolio.reconciliation.ingestion.rawingestion;

import com.portfolio.reconciliation.events.Source;
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
 * Payload cru recebido pela borda, persistido para auditoria e idempotência. Ver
 * docs/architecture.md §6.1, docs/ingestion/source-formats.md e ADR-0008.
 */
@Entity
@Table(name = "raw_ingestion")
public class RawIngestion {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  private Source source;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_payload")
  private String rawPayload;

  @Enumerated(EnumType.STRING)
  private RawIngestionStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "validation_errors")
  private String validationErrors;

  @Column(name = "idempotency_key")
  private String idempotencyKey;

  @Column(name = "trace_id")
  private UUID traceId;

  @Column(name = "published_event_id")
  private UUID publishedEventId;

  @Column(name = "received_at")
  private Instant receivedAt;

  protected RawIngestion() {
    // JPA
  }

  public RawIngestion(
      UUID id,
      Source source,
      String rawPayload,
      RawIngestionStatus status,
      String idempotencyKey,
      UUID traceId,
      Instant receivedAt) {
    this.id = id;
    this.source = source;
    this.rawPayload = rawPayload;
    this.status = status;
    this.idempotencyKey = idempotencyKey;
    this.traceId = traceId;
    this.receivedAt = receivedAt;
  }

  public UUID getId() {
    return id;
  }

  public Source getSource() {
    return source;
  }

  public String getRawPayload() {
    return rawPayload;
  }

  public RawIngestionStatus getStatus() {
    return status;
  }

  public void setStatus(RawIngestionStatus status) {
    this.status = status;
  }

  public String getValidationErrors() {
    return validationErrors;
  }

  public void setValidationErrors(String validationErrors) {
    this.validationErrors = validationErrors;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public UUID getTraceId() {
    return traceId;
  }

  public UUID getPublishedEventId() {
    return publishedEventId;
  }

  public void setPublishedEventId(UUID publishedEventId) {
    this.publishedEventId = publishedEventId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}
