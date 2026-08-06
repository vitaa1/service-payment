package com.portfolio.reconciliation.ingestion.web;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.ingestion.ingest.IngestionResult;
import com.portfolio.reconciliation.ingestion.ingest.IngestionService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Borda de ingestão: POST /ingestion/{source}. Valida síncrono (202 se ok, 400 se inválido),
 * honra Idempotency-Key. Ver docs/ingestion/source-formats.md.
 */
@RestController
public class IngestionController {

  private static final Map<String, Source> PATH_TO_SOURCE =
      Map.of(
          "gateway", Source.GATEWAY,
          "bank-statement", Source.BANK_STATEMENT,
          "internal-order", Source.INTERNAL_ORDER);

  private final IngestionService ingestionService;

  public IngestionController(IngestionService ingestionService) {
    this.ingestionService = ingestionService;
  }

  @PostMapping("/ingestion/{source}")
  public ResponseEntity<?> ingest(
      @PathVariable String source,
      @RequestBody String body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

    Source resolved = PATH_TO_SOURCE.get(source);
    if (resolved == null) {
      throw new UnknownSourceException(source);
    }

    IngestionResult result = ingestionService.ingest(resolved, body, idempotencyKey);
    if (result.accepted()) {
      return ResponseEntity.accepted()
          .body(new IngestionResponse(result.ingestionId(), result.traceId()));
    }
    return ResponseEntity.badRequest().body(new IngestionErrorResponse(result.errors()));
  }
}
