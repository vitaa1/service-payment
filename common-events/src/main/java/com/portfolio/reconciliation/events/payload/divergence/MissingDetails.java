package com.portfolio.reconciliation.events.payload.divergence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.portfolio.reconciliation.events.Source;
import java.util.List;

/** Fontes esperadas para o caso e quais delas ainda não chegaram. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MissingDetails(List<Source> expectedSources, List<Source> missingSources)
    implements DivergenceDetails {}
