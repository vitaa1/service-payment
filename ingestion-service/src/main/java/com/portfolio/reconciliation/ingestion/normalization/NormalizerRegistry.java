package com.portfolio.reconciliation.ingestion.normalization;

import com.portfolio.reconciliation.events.Source;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Seleciona o {@link SourceNormalizer} pela {@link Source}. Coleta todos os beans normalizer. */
@Component
public class NormalizerRegistry {

  private final Map<Source, SourceNormalizer<?>> bySource;

  public NormalizerRegistry(List<SourceNormalizer<?>> normalizers) {
    this.bySource =
        normalizers.stream()
            .collect(Collectors.toMap(SourceNormalizer::source, Function.identity()));
  }

  public SourceNormalizer<?> forSource(Source source) {
    SourceNormalizer<?> normalizer = bySource.get(source);
    if (normalizer == null) {
      throw new IllegalStateException("Sem normalizer para a fonte: " + source);
    }
    return normalizer;
  }
}
