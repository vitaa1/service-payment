package com.portfolio.reconciliation.events.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventPayload;
import com.portfolio.reconciliation.events.payload.divergence.DivergenceDetails;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload do evento {@code DivergenceDetected}. Ver docs/events/divergence-detected.md (fonte
 * da verdade). {@code details} é polimórfico, discriminado pelo campo irmão {@code
 * divergenceType} — o allowlist fechado é o {@code @JsonSubTypes} abaixo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DivergenceDetectedPayload(
    UUID caseId,
    String matchingKey,
    DivergenceType divergenceType,
    int caseVersion,
    String externalReference,
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
            property = "divergenceType")
        @JsonSubTypes({
          @JsonSubTypes.Type(value = DivergentDetails.class, name = "DIVERGENT"),
          @JsonSubTypes.Type(value = MissingDetails.class, name = "MISSING"),
          @JsonSubTypes.Type(value = DuplicateDetails.class, name = "DUPLICATE")
        })
        DivergenceDetails details,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant detectedAt)
    implements EventPayload {}
