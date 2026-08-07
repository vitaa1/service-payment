package com.portfolio.reconciliation.notification.email;

import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.divergence.DivergenceDetails;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import org.springframework.stereotype.Component;

/**
 * Monta o e-mail de alerta a partir de um {@code DivergenceDetected}. O corpo detalha a
 * divergência via um switch exaustivo sobre o sealed interface {@link DivergenceDetails} — o
 * compilador aponta o texto que falta se um novo tipo de divergência for criado.
 */
@Component
public class EmailComposer {

  public record EmailContent(String subject, String body) {}

  public EmailContent compose(EventEnvelope<DivergenceDetectedPayload> envelope) {
    DivergenceDetectedPayload payload = envelope.payload();
    String subject =
        "[Conciliação] Divergência %s no caso %s"
            .formatted(payload.divergenceType(), payload.matchingKey());
    String body =
        """
        Uma divergência foi detectada na conciliação de pagamentos.

        Caso: %s
        Referência externa: %s
        Tipo de divergência: %s
        Detectada em: %s

        %s

        ---
        caseId: %s
        eventId: %s
        traceId: %s
        """
            .formatted(
                payload.matchingKey(),
                payload.externalReference(),
                payload.divergenceType(),
                payload.detectedAt(),
                describe(payload.details()),
                payload.caseId(),
                envelope.eventId(),
                envelope.traceId());
    return new EmailContent(subject, body);
  }

  private String describe(DivergenceDetails details) {
    return switch (details) {
      case DivergentDetails d ->
          "Campo divergente: %s. Valores por fonte: %s".formatted(d.field(), d.values());
      case MissingDetails m ->
          "Fontes esperadas: %s. Fontes ausentes: %s"
              .formatted(m.expectedSources(), m.missingSources());
      case DuplicateDetails du ->
          "Fonte com registro duplicado: %s (%d ocorrências)"
              .formatted(du.source(), du.occurrences());
    };
  }
}
