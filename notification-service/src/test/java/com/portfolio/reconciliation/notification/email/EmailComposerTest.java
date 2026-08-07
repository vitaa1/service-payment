package com.portfolio.reconciliation.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailComposerTest {

  private final EmailComposer composer = new EmailComposer();

  @Test
  void componeAssuntoECorpoParaDivergent() {
    UUID caseId = UUID.randomUUID();
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            caseId,
            "chg_123",
            DivergenceType.DIVERGENT,
            2,
            "ext_ref_999",
            new DivergentDetails("amount", Map.of(Source.GATEWAY, "199.90", Source.BANK_STATEMENT, "189.90")),
            Instant.parse("2026-08-07T13:00:00Z"));
    EventEnvelope<DivergenceDetectedPayload> envelope =
        new EventEnvelope<>(
            UUID.randomUUID(), "DivergenceDetected", 1, Instant.now(), UUID.randomUUID(),
            "reconciliation-service", payload);

    EmailComposer.EmailContent content = composer.compose(envelope);

    assertThat(content.subject()).isEqualTo("[Conciliação] Divergência DIVERGENT no caso chg_123");
    assertThat(content.body())
        .contains(
            "chg_123",
            "ext_ref_999",
            "amount",
            "199.90",
            "189.90",
            caseId.toString(),
            envelope.eventId().toString(),
            envelope.traceId().toString());
  }

  @Test
  void componeCorpoParaMissing() {
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.randomUUID(),
            "chg_456",
            DivergenceType.MISSING,
            1,
            "chg_456",
            new MissingDetails(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), List.of(Source.INTERNAL_ORDER)),
            Instant.parse("2026-08-07T13:00:00Z"));
    EventEnvelope<DivergenceDetectedPayload> envelope =
        new EventEnvelope<>(
            UUID.randomUUID(), "DivergenceDetected", 1, Instant.now(), UUID.randomUUID(),
            "reconciliation-service", payload);

    EmailComposer.EmailContent content = composer.compose(envelope);

    assertThat(content.subject()).isEqualTo("[Conciliação] Divergência MISSING no caso chg_456");
    assertThat(content.body()).contains("INTERNAL_ORDER");
  }

  @Test
  void componeCorpoParaDuplicate() {
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.randomUUID(),
            "chg_789",
            DivergenceType.DUPLICATE,
            1,
            "chg_789",
            new DuplicateDetails(Source.GATEWAY, 2),
            Instant.parse("2026-08-07T13:00:00Z"));
    EventEnvelope<DivergenceDetectedPayload> envelope =
        new EventEnvelope<>(
            UUID.randomUUID(), "DivergenceDetected", 1, Instant.now(), UUID.randomUUID(),
            "reconciliation-service", payload);

    EmailComposer.EmailContent content = composer.compose(envelope);

    assertThat(content.subject()).isEqualTo("[Conciliação] Divergência DUPLICATE no caso chg_789");
    assertThat(content.body()).contains("GATEWAY", "2");
  }
}
