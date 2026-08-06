package com.portfolio.reconciliation.ingestion.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Evento a publicar, gravado na mesma transação do estado de negócio (ADR-0006). O {@code
 * payload} guarda o envelope completo já serializado (pronto para enviar verbatim).
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "event_type")
  private String eventType;

  @Column(name = "routing_key")
  private String routingKey;

  @Column(name = "trace_id")
  private UUID traceId;

  @JdbcTypeCode(SqlTypes.JSON)
  private String payload;

  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  private int attempts;

  protected OutboxEvent() {
    // JPA
  }

  public OutboxEvent(UUID eventId, String eventType, String routingKey, UUID traceId, String payload) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.routingKey = routingKey;
    this.traceId = traceId;
    this.payload = payload;
  }

  public Long getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getRoutingKey() {
    return routingKey;
  }

  public UUID getTraceId() {
    return traceId;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int attempts) {
    this.attempts = attempts;
  }
}
