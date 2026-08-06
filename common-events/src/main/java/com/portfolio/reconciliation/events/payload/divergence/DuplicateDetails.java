package com.portfolio.reconciliation.events.payload.divergence;

import com.portfolio.reconciliation.events.Source;

/** Fonte com registro repetido na mesma matching key, e quantas vezes apareceu. */
public record DuplicateDetails(Source source, int occurrences) implements DivergenceDetails {}
