package com.portfolio.reconciliation.ingestion.rawingestion;

/** Ciclo de vida de um registro bruto. Ver docs/ingestion/source-formats.md. */
public enum RawIngestionStatus {
  RECEIVED,
  VALIDATED,
  REJECTED
}
