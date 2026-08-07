package com.portfolio.reconciliation.reconciliation.evaluation;

import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.divergence.DivergenceDetails;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import com.portfolio.reconciliation.reconciliation.domain.NormalizedRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Avalia um caso de conciliação a partir das pernas presentes (ADR-0010). Precedência: {@code
 * DUPLICATE > DIVERGENT > MISSING > MATCHED}. Lógica de domínio pura, sem infraestrutura — testada
 * por unit test.
 */
public class CaseEvaluator {

  private final Set<Source> expectedSources;

  public CaseEvaluator(Set<Source> expectedSources) {
    this.expectedSources = expectedSources;
  }

  /** {@code records} deve ter ao menos um elemento — um caso sempre nasce de uma perna. */
  public CaseEvaluation evaluate(List<NormalizedRecord> records) {
    Map<Source, List<NormalizedRecord>> bySource = groupBySource(records);
    Map<Source, NormalizedRecord> representative = representativePerSource(bySource);
    List<Source> sourcesPresent = sortedSources(representative.keySet());
    NormalizedRecord consolidated = pickConsolidated(representative);

    Source duplicated = firstDuplicatedSource(bySource);
    if (duplicated != null) {
      DuplicateDetails details = new DuplicateDetails(duplicated, bySource.get(duplicated).size());
      return result(ReconciliationStatus.DUPLICATE, details, consolidated, sourcesPresent);
    }

    String divergentField = firstDivergentField(representative);
    if (divergentField != null) {
      DivergenceDetails details = buildDivergentDetails(divergentField, representative);
      return result(ReconciliationStatus.DIVERGENT, details, consolidated, sourcesPresent);
    }

    List<Source> missing = missingSources(sourcesPresent);
    if (!missing.isEmpty()) {
      MissingDetails details = new MissingDetails(sortedSources(expectedSources), missing);
      return result(ReconciliationStatus.MISSING, details, consolidated, sourcesPresent);
    }

    return result(ReconciliationStatus.MATCHED, null, consolidated, sourcesPresent);
  }

  private CaseEvaluation result(
      ReconciliationStatus status,
      DivergenceDetails details,
      NormalizedRecord consolidated,
      List<Source> sourcesPresent) {
    return new CaseEvaluation(
        status,
        details,
        consolidated.getAmount(),
        consolidated.getCurrency(),
        consolidated.getTransactionDate(),
        sourcesPresent);
  }

  private Map<Source, List<NormalizedRecord>> groupBySource(List<NormalizedRecord> records) {
    Map<Source, List<NormalizedRecord>> map = new EnumMap<>(Source.class);
    for (NormalizedRecord record : records) {
      map.computeIfAbsent(record.getSource(), s -> new ArrayList<>()).add(record);
    }
    return map;
  }

  /** Primeira fonte com mais de um registro, em ordem do enum (deterministico). */
  private Source firstDuplicatedSource(Map<Source, List<NormalizedRecord>> bySource) {
    for (Source source : Source.values()) {
      List<NormalizedRecord> records = bySource.get(source);
      if (records != null && records.size() > 1) {
        return source;
      }
    }
    return null;
  }

  private Map<Source, NormalizedRecord> representativePerSource(
      Map<Source, List<NormalizedRecord>> bySource) {
    Map<Source, NormalizedRecord> map = new EnumMap<>(Source.class);
    bySource.forEach((source, records) -> map.put(source, records.get(0)));
    return map;
  }

  private List<Source> sortedSources(Collection<Source> sources) {
    return sources.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
  }

  private List<Source> missingSources(List<Source> sourcesPresent) {
    return sortedSources(
        expectedSources.stream().filter(s -> !sourcesPresent.contains(s)).toList());
  }

  /** Prefere GATEWAY (fonte autoritativa); fallback para a primeira presente, em ordem do enum. */
  private NormalizedRecord pickConsolidated(Map<Source, NormalizedRecord> representative) {
    if (representative.containsKey(Source.GATEWAY)) {
      return representative.get(Source.GATEWAY);
    }
    for (Source source : Source.values()) {
      NormalizedRecord record = representative.get(source);
      if (record != null) {
        return record;
      }
    }
    throw new IllegalStateException("caso sem nenhum registro — invariante violada");
  }

  /** Precedência amount &gt; currency &gt; transactionDate. {@code null} se tudo consistente. */
  private String firstDivergentField(Map<Source, NormalizedRecord> representative) {
    if (representative.size() < 2) {
      return null;
    }
    Collection<NormalizedRecord> values = representative.values();
    if (!allEqual(values, NormalizedRecord::getAmount, (a, b) -> a.compareTo(b) == 0)) {
      return "amount";
    }
    if (!allEqual(values, NormalizedRecord::getCurrency, Object::equals)) {
      return "currency";
    }
    if (!allEqual(values, NormalizedRecord::getTransactionDate, Object::equals)) {
      return "transactionDate";
    }
    return null;
  }

  private <T> boolean allEqual(
      Collection<NormalizedRecord> records,
      Function<NormalizedRecord, T> extractor,
      BiPredicate<T, T> equality) {
    Iterator<NormalizedRecord> it = records.iterator();
    T first = extractor.apply(it.next());
    while (it.hasNext()) {
      if (!equality.test(first, extractor.apply(it.next()))) {
        return false;
      }
    }
    return true;
  }

  private DivergentDetails buildDivergentDetails(
      String field, Map<Source, NormalizedRecord> representative) {
    Map<Source, String> values = new LinkedHashMap<>();
    for (Source source : Source.values()) {
      NormalizedRecord record = representative.get(source);
      if (record != null) {
        values.put(source, fieldValueAsString(field, record));
      }
    }
    return new DivergentDetails(field, values);
  }

  private String fieldValueAsString(String field, NormalizedRecord record) {
    return switch (field) {
      case "amount" -> record.getAmount().toPlainString();
      case "currency" -> record.getCurrency();
      case "transactionDate" -> record.getTransactionDate().toString();
      default -> throw new IllegalStateException("campo desconhecido: " + field);
    };
  }
}
