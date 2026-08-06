package com.portfolio.reconciliation.ingestion.normalization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Pedido do sistema interno: tem id próprio e a externalReference de ligação. */
public record InternalOrderRequest(
    @NotBlank String orderId,
    @NotBlank String externalReference,
    @NotNull @Positive BigDecimal totalAmount,
    @NotBlank String currency,
    @NotNull LocalDate orderDate,
    String buyer) {}
