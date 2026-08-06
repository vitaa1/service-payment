package com.portfolio.reconciliation.ingestion.normalization;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.TransactionNormalizedPayload;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Normaliza o webhook de gateway: centavos → decimal, timestamp → data, metadados preservados. */
@Component
public class GatewayNormalizer implements SourceNormalizer<GatewayRequest> {

  @Override
  public Source source() {
    return Source.GATEWAY;
  }

  @Override
  public Class<GatewayRequest> requestType() {
    return GatewayRequest.class;
  }

  @Override
  public TransactionNormalizedPayload normalize(UUID ingestionId, GatewayRequest req) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (req.gatewayTxnId() != null) {
      metadata.put("gatewayTxnId", req.gatewayTxnId());
    }
    if (req.paymentMethod() != null) {
      metadata.put("paymentMethod", req.paymentMethod());
    }

    return new TransactionNormalizedPayload(
        ingestionId,
        Source.GATEWAY,
        req.chargeId(),
        BigDecimal.valueOf(req.amountInCents(), 2),
        req.currency(),
        req.paidAt().atZone(ZoneOffset.UTC).toLocalDate(),
        req.customerName(),
        metadata.isEmpty() ? null : metadata);
  }
}
