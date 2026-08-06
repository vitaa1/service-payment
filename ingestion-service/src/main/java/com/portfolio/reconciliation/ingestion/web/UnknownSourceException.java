package com.portfolio.reconciliation.ingestion.web;

/** Path {source} que não corresponde a nenhuma fonte conhecida. Tratada pelo advice → 404. */
public class UnknownSourceException extends RuntimeException {

  public UnknownSourceException(String source) {
    super("Fonte desconhecida: " + source);
  }
}
