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

  TransactionNormalizedPayload normalize(UUID ingestionId, T request);
}
