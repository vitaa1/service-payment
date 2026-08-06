package com.portfolio.reconciliation.ingestion.normalization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/** Payload bruto do webhook de {@code GATEWAY}. Ver docs/ingestion/source-formats.md. */
public record GatewayRequest(
    @NotBlank String chargeId,
    String gatewayTxnId,
    @NotNull @Positive Long amountInCents,
    @NotBlank String currency,
    @NotNull Instant paidAt,
    String customerName,
    String paymentMethod) {}
