package com.portfolio.reconciliation.events.payload.divergence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.portfolio.reconciliation.events.Source;
import java.util.Map;

/** Campo que não confere entre fontes, e o valor visto em cada uma. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DivergentDetails(String field, Map<Source, String> values)
    implements DivergenceDetails {}
