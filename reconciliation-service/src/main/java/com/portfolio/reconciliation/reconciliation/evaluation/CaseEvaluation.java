package com.portfolio.reconciliation.reconciliation.evaluation;

import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.divergence.DivergenceDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Resultado da avaliação de um caso (ADR-0010). {@code details} é {@code null} quando {@code
 * status} é {@code MATCHED}. Os campos consolidados vêm de um registro presente representativo
 * (preferindo {@code GATEWAY}, com fallback para qualquer presente).
 */
public record CaseEvaluation(
    ReconciliationStatus status,
    DivergenceDetails details,
    BigDecimal amount,
    String currency,
    LocalDate transactionDate,
    List<Source> sourcesPresent) {}
