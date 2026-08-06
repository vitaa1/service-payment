package com.portfolio.reconciliation.ingestion.normalization;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import java.util.UUID;

/**
 * Adapter de uma fonte: mapeia o payload bruto (já validado) para o schema canônico. Ver
 * docs/ingestion/source-formats.md.
 */
public interface SourceNormalizer<T> {

  Source source();

  /** Tipo do DTO bruto desta fonte — usado para desserializar o corpo da request. */
  Class<T> requestType();

  TransactionNormalizedPayload normalize(UUID ingestionId, T request);
}
