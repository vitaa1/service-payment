package com.portfolio.reconciliation.ingestion.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.ingestion.normalization.BankStatementNormalizer;
import com.portfolio.reconciliation.ingestion.normalization.GatewayNormalizer;
import com.portfolio.reconciliation.ingestion.normalization.InternalOrderNormalizer;
import com.portfolio.reconciliation.ingestion.normalization.NormalizerRegistry;
import com.portfolio.reconciliation.ingestion.outbox.OutboxEvent;
import com.portfolio.reconciliation.ingestion.outbox.OutboxRepository;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestion;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestionRepository;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestionStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IngestionServiceTest {

  private static final String GATEWAY_VALIDO =
      "{\"chargeId\":\"chg_1\",\"gatewayTxnId\":\"txn_1\",\"amountInCents\":19990,"
          + "\"currency\":\"BRL\",\"paidAt\":\"2026-07-29T13:45:00Z\"}";

  private final RawIngestionRepository rawRepository = mock(RawIngestionRepository.class);
  private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
  private final NormalizerRegistry registry =
      new NormalizerRegistry(
          List.of(
              new GatewayNormalizer(), new BankStatementNormalizer(), new InternalOrderNormalizer()));
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private final IngestionService service =
      new IngestionService(rawRepository, outboxRepository, registry, objectMapper, validator);

  @Test
  void deveAceitarGatewayValidoEGravarRawEOutbox() {
    IngestionResult result = service.ingest(Source.GATEWAY, GATEWAY_VALIDO, null);

    assertTrue(result.accepted());

    ArgumentCaptor<RawIngestion> rawCaptor = ArgumentCaptor.forClass(RawIngestion.class);
    verify(rawRepository).save(rawCaptor.capture());
    assertEquals(RawIngestionStatus.VALIDATED, rawCaptor.getValue().getStatus());
    verify(outboxRepository).save(any(OutboxEvent.class));
  }

  @Test
  void deveRejeitarQuandoFaltaCampoObrigatorioSemPublicar() {
    String semCharge = "{\"amountInCents\":19990,\"currency\":\"BRL\",\"paidAt\":\"2026-07-29T13:45:00Z\"}";

    IngestionResult result = service.ingest(Source.GATEWAY, semCharge, null);

    assertFalse(result.accepted());
    assertFalse(result.errors().isEmpty());
    ArgumentCaptor<RawIngestion> rawCaptor = ArgumentCaptor.forClass(RawIngestion.class);
    verify(rawRepository).save(rawCaptor.capture());
    assertEquals(RawIngestionStatus.REJECTED, rawCaptor.getValue().getStatus());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void replayComKeyValidadaDeveDevolverMesmoIdSemNovoSave() {
    UUID existingId = UUID.randomUUID();
    RawIngestion existente = validado(existingId);
    when(rawRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(existente));

    IngestionResult result = service.ingest(Source.GATEWAY, GATEWAY_VALIDO, "k1");

    assertTrue(result.accepted());
    assertEquals(existingId, result.ingestionId());
    verify(rawRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  void replayComKeyRejeitadaDeveDevolverOsErrosOriginais() {
    RawIngestion rejeitado = rejeitado("[\"chargeId não deve estar em branco\"]");
    when(rawRepository.findByIdempotencyKey("k2")).thenReturn(Optional.of(rejeitado));

    IngestionResult result = service.ingest(Source.GATEWAY, GATEWAY_VALIDO, "k2");

    assertFalse(result.accepted());
    assertEquals(List.of("chargeId não deve estar em branco"), result.errors());
  }

  @Test
  void deveRejeitarChargeIdComCaracteresDeControleSemPublicar() {
    String chargeIdComCrLf =
        "{\"chargeId\":\"chg_1\\r\\nSubject: hackeado\",\"amountInCents\":19990,"
            + "\"currency\":\"BRL\",\"paidAt\":\"2026-07-29T13:45:00Z\"}";

    IngestionResult result = service.ingest(Source.GATEWAY, chargeIdComCrLf, null);

    assertFalse(result.accepted());
    assertMensagemDeCaracteresNaoPermitidosSemVazarRegex(result, "chargeId");
    verify(outboxRepository, never()).save(any());
    assertRawGravadoComoRejected();
  }

  @Test
  void deveRejeitarReferenceComCaracteresDeControleSemPublicar() {
    String referenceComCrLf =
        "{\"reference\":\"chg_1\\r\\nSubject: hackeado\",\"value\":\"199.90\","
            + "\"date\":\"2026-07-29\"}";

    IngestionResult result = service.ingest(Source.BANK_STATEMENT, referenceComCrLf, null);

    assertFalse(result.accepted());
    assertMensagemDeCaracteresNaoPermitidosSemVazarRegex(result, "reference");
    verify(outboxRepository, never()).save(any());
    assertRawGravadoComoRejected();
  }

  @Test
  void deveRejeitarExternalReferenceComCaracteresDeControleSemPublicar() {
    String externalReferenceComCrLf =
        "{\"orderId\":\"ORD-1\",\"externalReference\":\"chg_1\\r\\nSubject: hackeado\","
            + "\"totalAmount\":\"199.90\",\"currency\":\"BRL\",\"orderDate\":\"2026-07-29\"}";

    IngestionResult result = service.ingest(Source.INTERNAL_ORDER, externalReferenceComCrLf, null);

    assertFalse(result.accepted());
    assertMensagemDeCaracteresNaoPermitidosSemVazarRegex(result, "externalReference");
    verify(outboxRepository, never()).save(any());
    assertRawGravadoComoRejected();
  }

  @Test
  void deveAceitarChargeIdComPontuacaoUsualDeReferenciaDePagamento() {
    String chargeIdComPontuacaoUsual =
        "{\"chargeId\":\"chg-2026:01.01_x\",\"amountInCents\":19990,"
            + "\"currency\":\"BRL\",\"paidAt\":\"2026-07-29T13:45:00Z\"}";

    IngestionResult result = service.ingest(Source.GATEWAY, chargeIdComPontuacaoUsual, null);

    assertTrue(result.accepted());
  }

  @Test
  void deveRejeitarChargeIdMuitoLongoSemPublicar() {
    String chargeIdMuitoLongo =
        "{\"chargeId\":\"" + "a".repeat(256) + "\",\"amountInCents\":19990,"
            + "\"currency\":\"BRL\",\"paidAt\":\"2026-07-29T13:45:00Z\"}";

    IngestionResult result = service.ingest(Source.GATEWAY, chargeIdMuitoLongo, null);

    assertFalse(result.accepted());
    assertFalse(result.errors().isEmpty());
    verify(outboxRepository, never()).save(any());
  }

  private void assertMensagemDeCaracteresNaoPermitidosSemVazarRegex(
      IngestionResult result, String campo) {
    assertTrue(
        result.errors().stream()
            .anyMatch(e -> e.contains(campo) && e.contains("caracteres não permitidos")),
        () -> "esperava erro de caracteres não permitidos para " + campo + ", veio " + result.errors());
    assertTrue(
        result.errors().stream().noneMatch(e -> e.contains("A-Za-z")),
        () -> "mensagem de erro não deve vazar o regex interno: " + result.errors());
  }

  private void assertRawGravadoComoRejected() {
    ArgumentCaptor<RawIngestion> rawCaptor = ArgumentCaptor.forClass(RawIngestion.class);
    verify(rawRepository).save(rawCaptor.capture());
    assertEquals(RawIngestionStatus.REJECTED, rawCaptor.getValue().getStatus());
  }

  @Test
  void idempotencyKeyMuitoLongaDeveRejeitarSemPersistir() {
    String keyLonga = "x".repeat(256);

    IngestionResult result = service.ingest(Source.GATEWAY, GATEWAY_VALIDO, keyLonga);

    assertFalse(result.accepted());
    assertFalse(result.errors().isEmpty());
    verify(rawRepository, never()).save(any());
  }

  private RawIngestion validado(UUID id) {
    return new RawIngestion(
        id, Source.GATEWAY, "{}", RawIngestionStatus.VALIDATED, "k1", UUID.randomUUID(), Instant.now());
  }

  private RawIngestion rejeitado(String errorsJson) {
    RawIngestion raw =
        new RawIngestion(
            UUID.randomUUID(),
            Source.GATEWAY,
            "{}",
            RawIngestionStatus.REJECTED,
            "k2",
            UUID.randomUUID(),
            Instant.now());
    raw.setValidationErrors(errorsJson);
    return raw;
  }
}
