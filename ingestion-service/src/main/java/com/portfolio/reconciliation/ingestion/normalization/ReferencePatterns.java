package com.portfolio.reconciliation.ingestion.normalization;

/**
 * Allowlist compartilhada pelos campos que alimentam {@code externalReference} (ADR-0009). Esse
 * valor propaga sem transformação até virar Subject de e-mail no notification-service, então
 * bloqueia CR/LF e qualquer caractere fora de um identificador de referência de pagamento comum.
 */
final class ReferencePatterns {

  /** Mesma allowlist usada nos três records (InternalOrderRequest, GatewayRequest,
   * BankStatementRequest); tamanho limitado a 255 para caber em {@code matching_key VARCHAR(255)}
   * do reconciliation-service (ADR-0009). */
  static final String EXTERNAL_REFERENCE = "[A-Za-z0-9_.:-]{1,255}";

  static final String EXTERNAL_REFERENCE_MESSAGE = "contém caracteres não permitidos";

  private ReferencePatterns() {}
}
