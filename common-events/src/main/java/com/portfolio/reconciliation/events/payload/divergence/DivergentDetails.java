package com.portfolio.reconciliation.events.payload.divergence;

import com.portfolio.reconciliation.events.Source;
import java.util.Map;

/** Campo que não confere entre fontes, e o valor visto em cada uma. */
public record DivergentDetails(String field, Map<Source, String> values)
    implements DivergenceDetails {}
