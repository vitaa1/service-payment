package com.portfolio.reconciliation.reconciliation.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.portfolio.reconciliation.events.ReconciliationStatus;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import com.portfolio.reconciliation.reconciliation.domain.NormalizedRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseEvaluatorTest {

  private static final LocalDate DATE = LocalDate.parse("2026-07-29");

  private final CaseEvaluator evaluator =
      new CaseEvaluator(Set.of(Source.GATEWAY, Source.INTERNAL_ORDER));

  private NormalizedRecord rec(Source source, String amount, String currency, LocalDate date) {
    return new NormalizedRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        source,
        "chg_1",
        new BigDecimal(amount),
        currency,
        date,
        UUID.randomUUID());
  }

  @Test
  void devePrecisarApenasDasFontesEsperadasParaMatched() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "199.90", "BRL", DATE)));

    assertEquals(ReconciliationStatus.MATCHED, result.status());
    assertNull(result.details());
    assertEquals(new BigDecimal("199.90"), result.amount());
    assertEquals("BRL", result.currency());
    assertEquals(DATE, result.transactionDate());
    assertEquals(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), result.sourcesPresent());
  }

  @Test
  void bankStatementConsistentePermaneceMatched() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "199.90", "BRL", DATE),
                rec(Source.BANK_STATEMENT, "199.90", "BRL", DATE)));

    assertEquals(ReconciliationStatus.MATCHED, result.status());
  }

  @Test
  void amountComEscalasDiferentesEhConsideradoIgual() {
    // 199.90 == 199.9000 (compareTo, não equals) — não pode gerar falso DIVERGENT.
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "199.9000", "BRL", DATE)));

    assertEquals(ReconciliationStatus.MATCHED, result.status());
  }

  @Test
  void faltaFonteEsperadaGeraMissingComExpectedEMissingSources() {
    CaseEvaluation result = evaluator.evaluate(List.of(rec(Source.GATEWAY, "199.90", "BRL", DATE)));

    assertEquals(ReconciliationStatus.MISSING, result.status());
    MissingDetails details = (MissingDetails) result.details();
    assertEquals(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), details.expectedSources());
    assertEquals(List.of(Source.INTERNAL_ORDER), details.missingSources());
  }

  @Test
  void amountDivergenteGeraDivergentComFieldAmount() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "189.90", "BRL", DATE)));

    assertEquals(ReconciliationStatus.DIVERGENT, result.status());
    DivergentDetails details = (DivergentDetails) result.details();
    assertEquals("amount", details.field());
    assertEquals(
        0,
        new BigDecimal("199.90")
            .compareTo(new BigDecimal(details.values().get(Source.GATEWAY))));
    assertEquals(
        0,
        new BigDecimal("189.90")
            .compareTo(new BigDecimal(details.values().get(Source.INTERNAL_ORDER))));
  }

  @Test
  void currencyDivergenteGeraDivergentComFieldCurrency() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "199.90", "USD", DATE)));

    assertEquals(ReconciliationStatus.DIVERGENT, result.status());
    DivergentDetails details = (DivergentDetails) result.details();
    assertEquals("currency", details.field());
    assertEquals("BRL", details.values().get(Source.GATEWAY));
    assertEquals("USD", details.values().get(Source.INTERNAL_ORDER));
  }

  @Test
  void transactionDateDivergenteGeraDivergentComFieldTransactionDate() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "199.90", "BRL", DATE.plusDays(1))));

    assertEquals(ReconciliationStatus.DIVERGENT, result.status());
    DivergentDetails details = (DivergentDetails) result.details();
    assertEquals("transactionDate", details.field());
  }

  @Test
  void precedenciaDeCamposDivergentesEhAmountAntesDeCurrency() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "189.90", "USD", DATE)));

    DivergentDetails details = (DivergentDetails) result.details();
    assertEquals("amount", details.field());
  }

  @Test
  void mesmaFonteDuasVezesGeraDuplicateComOccurrences() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "199.90", "BRL", DATE)));

    assertEquals(ReconciliationStatus.DUPLICATE, result.status());
    DuplicateDetails details = (DuplicateDetails) result.details();
    assertEquals(Source.GATEWAY, details.source());
    assertEquals(2, details.occurrences());
  }

  @Test
  void duplicatePrecedeDivergentEMissing() {
    // GATEWAY duplicado E divergente entre si; INTERNAL_ORDER ausente. Deve reportar DUPLICATE.
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.GATEWAY, "189.90", "BRL", DATE)));

    assertEquals(ReconciliationStatus.DUPLICATE, result.status());
  }

  @Test
  void duasFontesDuplicadasSimultaneamenteReportaAPrimeiraEmOrdemDoEnum() {
    // GATEWAY (ordinal 0) e INTERNAL_ORDER (ordinal 2) duplicados — o desempate é
    // determinístico pela ordem do enum Source: GATEWAY vence.
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.INTERNAL_ORDER, "199.90", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "199.90", "BRL", DATE),
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.GATEWAY, "199.90", "BRL", DATE)));

    assertEquals(ReconciliationStatus.DUPLICATE, result.status());
    DuplicateDetails details = (DuplicateDetails) result.details();
    assertEquals(Source.GATEWAY, details.source());
  }

  @Test
  void divergentPrecedeMissingQuandoAmbosSeAplicam() {
    // GATEWAY e BANK_STATEMENT divergem; INTERNAL_ORDER (esperado) está ausente.
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.GATEWAY, "199.90", "BRL", DATE),
                rec(Source.BANK_STATEMENT, "189.90", "BRL", DATE)));

    assertEquals(ReconciliationStatus.DIVERGENT, result.status());
  }

  @Test
  void consolidadoPrefereGatewayQuandoPresente() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.INTERNAL_ORDER, "199.90", "BRL", DATE),
                rec(Source.GATEWAY, "199.90", "BRL", DATE)));

    assertEquals(new BigDecimal("199.90"), result.amount());
  }

  @Test
  void consolidadoUsaFallbackQuandoGatewayAusente() {
    CaseEvaluation result =
        evaluator.evaluate(
            List.of(
                rec(Source.BANK_STATEMENT, "50.00", "BRL", DATE),
                rec(Source.INTERNAL_ORDER, "50.00", "BRL", DATE)));

    assertEquals(new BigDecimal("50.00"), result.amount());
    assertEquals(ReconciliationStatus.MISSING, result.status());
  }
}
