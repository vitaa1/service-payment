package com.portfolio.reconciliation.notification.domain;

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
 * Uma tentativa de notificação por {@code DivergenceDetected}, uma linha por {@code eventId}
 * (dedup, ADR-0011). {@code caseId} é referência lógica ao {@code reconciliation_case} do
 * reconciliation-service — sem FK cruzando banco (ADR-0003). Ver docs/architecture.md §6.3.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog {

  @Id private UUID id;

  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "case_id")
  private UUID caseId;

  private String channel;

  private String recipient;

  @Enumerated(EnumType.STRING)
  private NotificationStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload_summary")
  private String payloadSummary;

  @Column(name = "trace_id")
  private UUID traceId;

  @Column(name = "sent_at")
  private Instant sentAt;

  protected NotificationLog() {
    // JPA
  }

  public NotificationLog(
      UUID id, UUID eventId, UUID caseId, String channel, String recipient, UUID traceId) {
    this.id = id;
    this.eventId = eventId;
    this.caseId = caseId;
    this.channel = channel;
    this.recipient = recipient;
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

  public String getChannel() {
    return channel;
  }

  public String getRecipient() {
    return recipient;
  }

  public NotificationStatus getStatus() {
    return status;
  }

  public void setStatus(NotificationStatus status) {
    this.status = status;
  }

  public String getPayloadSummary() {
    return payloadSummary;
  }

  public void setPayloadSummary(String payloadSummary) {
    this.payloadSummary = payloadSummary;
  }

  public UUID getTraceId() {
    return traceId;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public void setSentAt(Instant sentAt) {
    this.sentAt = sentAt;
  }
}
