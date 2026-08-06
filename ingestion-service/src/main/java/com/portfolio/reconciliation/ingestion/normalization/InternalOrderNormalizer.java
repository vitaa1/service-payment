package com.portfolio.reconciliation.ingestion.normalization;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Normaliza o pedido interno: externalReference direta, orderId preservado no metadata. */
@Component
public class InternalOrderNormalizer implements SourceNormalizer<InternalOrderRequest> {

  @Override
  public Source source() {
    return Source.INTERNAL_ORDER;
  }

  @Override
  public TransactionNormalizedPayload normalize(UUID ingestionId, InternalOrderRequest req) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("orderId", req.orderId());

    return new TransactionNormalizedPayload(
        ingestionId,
        Source.INTERNAL_ORDER,
        req.externalReference(),
        req.totalAmount(),
        req.currency(),
        req.orderDate(),
        req.buyer(),
        metadata);
  }
}
