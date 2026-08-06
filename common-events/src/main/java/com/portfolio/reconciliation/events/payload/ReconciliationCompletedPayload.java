package com.portfolio.reconciliation.events.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.portfolio.reconciliation.events.EventPayload;
import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload do evento {@code ReconciliationCompleted}. Ver
 * docs/events/reconciliation-completed.md (fonte da verdade). Campos opcionais ausentes são
 * omitidos do JSON (não serializados como {@code null}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconciliationCompletedPayload(
    UUID caseId,
    String matchingKey,
    ReconciliationStatus status,
    int caseVersion,
    String externalReference,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
    String currency,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate transactionDate,
    List<Source> sourcesPresent,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant evaluatedAt)
    implements EventPayload {}
