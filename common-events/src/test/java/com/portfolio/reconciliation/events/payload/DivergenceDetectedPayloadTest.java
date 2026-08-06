package com.portfolio.reconciliation.events.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DivergenceDetectedPayloadTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void deveSerializarDetailsDivergentAoLadoDoDivergenceType() throws Exception {
    DivergentDetails details =
        new DivergentDetails(
            "amount", Map.of(Source.GATEWAY, "199.90", Source.BANK_STATEMENT, "189.90"));

    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.fromString("d1e2f3a4-b5c6-4a7e-8f90-1a2b3c4d5e6f"),
            "chg_9f8e7d6c5b4a|199.90|2026-07-29",
            DivergenceType.DIVERGENT,
            2,
            "chg_9f8e7d6c5b4a",
            details,
            Instant.parse("2026-07-29T13:47:02.300Z"));

    ObjectNode json = (ObjectNode) mapper.valueToTree(payload);

    assertEquals("DIVERGENT", json.get("divergenceType").asText());
    assertEquals("amount", json.get("details").get("field").asText());
    assertEquals("199.90", json.get("details").get("values").get("GATEWAY").asText());
  }

  @Test
  void deveDesserializarDetailsParaOSubtipoCorretoConformeDivergenceType() throws Exception {
    String json =
        "{"
            + "\"caseId\":\"d1e2f3a4-b5c6-4a7e-8f90-1a2b3c4d5e6f\","
            + "\"matchingKey\":\"chg_9f8e7d6c5b4a|199.90|2026-07-29\","
            + "\"divergenceType\":\"DIVERGENT\","
            + "\"caseVersion\":2,"
            + "\"externalReference\":\"chg_9f8e7d6c5b4a\","
            + "\"details\":{\"field\":\"amount\",\"values\":{\"GATEWAY\":\"199.90\",\"BANK_STATEMENT\":\"189.90\"}},"
            + "\"detectedAt\":\"2026-07-29T13:47:02.300Z\""
            + "}";

    DivergenceDetectedPayload payload = mapper.readValue(json, DivergenceDetectedPayload.class);

    assertEquals(DivergenceType.DIVERGENT, payload.divergenceType());
    assertEquals(true, payload.details() instanceof DivergentDetails);
    DivergentDetails details = (DivergentDetails) payload.details();
    assertEquals("amount", details.field());
    assertEquals("199.90", details.values().get(Source.GATEWAY));
  }

  @Test
  void deveDesserializarDetailsParaMissingDetails() throws Exception {
    String json =
        "{"
            + "\"caseId\":\"d1e2f3a4-b5c6-4a7e-8f90-1a2b3c4d5e6f\","
            + "\"matchingKey\":\"chg_9f8e7d6c5b4a|199.90|2026-07-29\","
            + "\"divergenceType\":\"MISSING\","
            + "\"caseVersion\":1,"
            + "\"externalReference\":\"chg_9f8e7d6c5b4a\","
            + "\"details\":{\"expectedSources\":[\"GATEWAY\",\"INTERNAL_ORDER\"],\"missingSources\":[\"INTERNAL_ORDER\"]},"
            + "\"detectedAt\":\"2026-07-29T13:47:02.300Z\""
            + "}";

    DivergenceDetectedPayload payload = mapper.readValue(json, DivergenceDetectedPayload.class);

    assertEquals(true, payload.details() instanceof MissingDetails);
    MissingDetails details = (MissingDetails) payload.details();
    assertEquals(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), details.expectedSources());
    assertEquals(List.of(Source.INTERNAL_ORDER), details.missingSources());
  }

  @Test
  void deveDesserializarDetailsParaDuplicateDetails() throws Exception {
    String json =
        "{"
            + "\"caseId\":\"d1e2f3a4-b5c6-4a7e-8f90-1a2b3c4d5e6f\","
            + "\"matchingKey\":\"chg_9f8e7d6c5b4a|199.90|2026-07-29\","
            + "\"divergenceType\":\"DUPLICATE\","
            + "\"caseVersion\":1,"
            + "\"externalReference\":\"chg_9f8e7d6c5b4a\","
            + "\"details\":{\"source\":\"GATEWAY\",\"occurrences\":2},"
            + "\"detectedAt\":\"2026-07-29T13:47:02.300Z\""
            + "}";

    DivergenceDetectedPayload payload = mapper.readValue(json, DivergenceDetectedPayload.class);

    assertEquals(true, payload.details() instanceof DuplicateDetails);
    DuplicateDetails details = (DuplicateDetails) payload.details();
    assertEquals(Source.GATEWAY, details.source());
    assertEquals(2, details.occurrences());
  }
}
