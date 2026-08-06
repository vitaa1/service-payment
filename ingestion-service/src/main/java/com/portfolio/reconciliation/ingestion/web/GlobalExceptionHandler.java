package com.portfolio.reconciliation.ingestion.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratamento central de erros da borda. Garante um corpo consistente ({@link
 * IngestionErrorResponse}) e que nunca vaza stack trace ou detalhe interno, independente de
 * config futura do Spring.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(UnknownSourceException.class)
  public ResponseEntity<IngestionErrorResponse> handleUnknownSource(UnknownSourceException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new IngestionErrorResponse(List.of(ex.getMessage())));
  }
}
