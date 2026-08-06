package com.portfolio.reconciliation.ingestion.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Path {source} que não corresponde a nenhuma fonte conhecida → 404. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UnknownSourceException extends RuntimeException {

  public UnknownSourceException(String source) {
    super("Fonte desconhecida: " + source);
  }
}
