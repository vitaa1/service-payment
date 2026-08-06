package com.portfolio.reconciliation.ingestion.normalization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Linha de extrato bancário. Não carrega currency (assume-se BRL). Ver source-formats.md. */
public record BankStatementRequest(
    @NotBlank String reference,
    @NotNull @Positive BigDecimal value,
    @NotNull LocalDate date,
    String description) {}
