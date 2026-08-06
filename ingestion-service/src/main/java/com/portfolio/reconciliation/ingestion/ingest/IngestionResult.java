package com.portfolio.reconciliation.ingestion.ingest;

import java.util.List;
import java.util.UUID;

/** Resultado do processamento de uma ingestão. {@code accepted} → 202; senão → 400 com erros. */
public record IngestionResult(
    UUID ingestionId, UUID traceId, boolean accepted, List<String> errors) {

  public static IngestionResult accepted(UUID ingestionId, UUID traceId) {
    return new IngestionResult(ingestionId, traceId, true, List.of());
  }

  public static IngestionResult rejected(UUID ingestionId, UUID traceId, List<String> errors) {
    return new IngestionResult(ingestionId, traceId, false, List.copyOf(errors));
  }
}
