package com.portfolio.reconciliation.events.payload.divergence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.portfolio.reconciliation.events.Source;

/** Fonte com registro repetido na mesma matching key, e quantas vezes apareceu. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DuplicateDetails(Source source, int occurrences) implements DivergenceDetails {}
