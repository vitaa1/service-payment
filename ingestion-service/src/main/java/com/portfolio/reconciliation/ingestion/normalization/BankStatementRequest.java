package com.portfolio.reconciliation.ingestion.normalization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Linha de extrato bancário. Não carrega currency (assume-se BRL). Ver source-formats.md. */
public record BankStatementRequest(
    @NotBlank
        @Pattern(
            regexp = ReferencePatterns.EXTERNAL_REFERENCE,
            message = ReferencePatterns.EXTERNAL_REFERENCE_MESSAGE)
        String reference,
    @NotNull @Positive BigDecimal value,
    @NotNull LocalDate date,
    String description) {}
