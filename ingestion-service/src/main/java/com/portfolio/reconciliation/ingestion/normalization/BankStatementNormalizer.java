package com.portfolio.reconciliation.ingestion.normalization;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Normaliza a linha de extrato: valor já decimal, currency default BRL. */
@Component
public class BankStatementNormalizer implements SourceNormalizer<BankStatementRequest> {

  private static final String DEFAULT_CURRENCY = "BRL";

  @Override
  public Source source() {
    return Source.BANK_STATEMENT;
  }

  @Override
  public TransactionNormalizedPayload normalize(UUID ingestionId, BankStatementRequest req) {
    return new TransactionNormalizedPayload(
        ingestionId,
        Source.BANK_STATEMENT,
        req.reference(),
        req.value(),
        DEFAULT_CURRENCY,
        req.date(),
        req.description(),
        null);
  }
}
