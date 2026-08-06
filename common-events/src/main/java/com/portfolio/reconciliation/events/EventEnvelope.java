package com.portfolio.reconciliation.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * Envelope comum a todo evento publicado em {@code payments.events}. Ver
 * docs/events/envelope.md (fonte da verdade).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T extends EventPayload>(
    UUID eventId,
    String eventType,
    int eventVersion,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
    UUID traceId,
    String producer,
    T payload) {}
