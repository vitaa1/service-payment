package com.portfolio.reconciliation.ingestion.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.ingestion.outbox.OutboxRepository;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestionRepository;
import com.portfolio.reconciliation.ingestion.rawingestion.RawIngestionStatus;
import com.portfolio.reconciliation.ingestion.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** POST /ingestion/{source} de ponta a ponta contra Postgres real (Testcontainers). */
@AutoConfigureMockMvc
class IngestionControllerIT extends AbstractIntegrationTest {

  private static final String GATEWAY_VALIDO =
      """
      {"chargeId":"chg_1","gatewayTxnId":"txn_1","amountInCents":19990,"currency":"BRL",
       "paidAt":"2026-07-29T13:45:00Z","customerName":"Loja","paymentMethod":"credit_card"}
      """;

  @Autowired MockMvc mockMvc;
  @Autowired RawIngestionRepository rawRepository;
  @Autowired OutboxRepository outboxRepository;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void limpa() {
    outboxRepository.deleteAll();
    rawRepository.deleteAll();
  }

  @Test
  void gatewayValidoDeveRetornar202EGravarRawEOutbox() throws Exception {
    mockMvc
        .perform(
            post("/ingestion/gateway").contentType(MediaType.APPLICATION_JSON).content(GATEWAY_VALIDO))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.ingestionId").exists())
        .andExpect(jsonPath("$.traceId").exists());

    assertEquals(1, rawRepository.count());
    assertEquals(RawIngestionStatus.VALIDATED, rawRepository.findAll().get(0).getStatus());
    assertEquals(1, outboxRepository.count(), "deve gravar exatamente um evento na outbox");
  }

  @Test
  void gatewaySemChargeIdDeveRetornar400EGravarRejectedSemOutbox() throws Exception {
    String semCharge =
        "{\"amountInCents\":19990,\"currency\":\"BRL\",\"paidAt\":\"2026-07-29T13:45:00Z\"}";

    mockMvc
        .perform(post("/ingestion/gateway").contentType(MediaType.APPLICATION_JSON).content(semCharge))
        .andExpect(status().isBadRequest());

    assertEquals(1, rawRepository.count());
    assertEquals(RawIngestionStatus.REJECTED, rawRepository.findAll().get(0).getStatus());
    assertEquals(0, outboxRepository.count(), "rejeitado não publica");
  }

  @Test
  void mesmaIdempotencyKeyDeveDevolverMesmoIngestionIdSemDuplicar() throws Exception {
    String primeira =
        mockMvc
            .perform(
                post("/ingestion/gateway")
                    .header("Idempotency-Key", "k1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(GATEWAY_VALIDO))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String segunda =
        mockMvc
            .perform(
                post("/ingestion/gateway")
                    .header("Idempotency-Key", "k1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(GATEWAY_VALIDO))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode idPrimeira = objectMapper.readTree(primeira).get("ingestionId");
    JsonNode idSegunda = objectMapper.readTree(segunda).get("ingestionId");
    assertEquals(idPrimeira, idSegunda, "reentrega deve devolver o mesmo ingestionId");

    assertEquals(1, rawRepository.count(), "reentrega não cria nova linha");
    assertEquals(1, outboxRepository.count(), "reentrega não republica");
  }

  @Test
  void fonteDesconhecidaDeveRetornar404() throws Exception {
    mockMvc
        .perform(post("/ingestion/pix").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound());
  }
}
