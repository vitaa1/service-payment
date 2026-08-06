package com.portfolio.reconciliation.events.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden JSON: divergence-detected.json é cópia reviewada do exemplo em
 * docs/events/divergence-detected.md. As fixtures -missing/-duplicate usam os snippets de
 * "details" documentados na seção "details por tipo" do mesmo doc, dentro do envelope padrão.
 */
class DivergenceDetectedGoldenJsonTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private JsonNode readFixture(String resourcePath) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      return mapper.readTree(in);
    }
  }

  private EventEnvelope<DivergenceDetectedPayload> readEnvelope(JsonNode node) throws Exception {
    return mapper.readValue(
        node.traverse(), new TypeReference<EventEnvelope<DivergenceDetectedPayload>>() {});
  }

  @Test
  void deveRoundtripDivergent() throws Exception {
    JsonNode original = readFixture("/contracts/divergence-detected.json");

    EventEnvelope<DivergenceDetectedPayload> envelope = readEnvelope(original);
    DivergenceDetectedPayload payload = envelope.payload();

    assertEquals(DivergenceType.DIVERGENT, payload.divergenceType());
    assertEquals(true, payload.details() instanceof DivergentDetails);
    DivergentDetails details = (DivergentDetails) payload.details();
    assertEquals("amount", details.field());
    assertEquals("189.90", details.values().get(Source.BANK_STATEMENT));

    assertEquals(original, mapper.valueToTree(envelope));
  }

  @Test
  void deveRoundtripMissing() throws Exception {
    JsonNode original = readFixture("/contracts/divergence-detected-missing.json");

    EventEnvelope<DivergenceDetectedPayload> envelope = readEnvelope(original);
    DivergenceDetectedPayload payload = envelope.payload();

    assertEquals(DivergenceType.MISSING, payload.divergenceType());
    assertEquals(true, payload.details() instanceof MissingDetails);
    MissingDetails details = (MissingDetails) payload.details();
    assertEquals(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), details.expectedSources());

    assertEquals(original, mapper.valueToTree(envelope));
  }

  @Test
  void deveRoundtripDuplicate() throws Exception {
    JsonNode original = readFixture("/contracts/divergence-detected-duplicate.json");

    EventEnvelope<DivergenceDetectedPayload> envelope = readEnvelope(original);
    DivergenceDetectedPayload payload = envelope.payload();

    assertEquals(DivergenceType.DUPLICATE, payload.divergenceType());
    assertEquals(true, payload.details() instanceof DuplicateDetails);
    DuplicateDetails details = (DuplicateDetails) payload.details();
    assertEquals(Source.GATEWAY, details.source());
    assertEquals(2, details.occurrences());

    assertEquals(original, mapper.valueToTree(envelope));
  }
}
