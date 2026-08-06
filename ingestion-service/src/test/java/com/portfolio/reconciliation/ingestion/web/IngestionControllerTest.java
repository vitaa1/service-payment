package com.portfolio.reconciliation.ingestion.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.ingestion.ingest.IngestionResult;
import com.portfolio.reconciliation.ingestion.ingest.IngestionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Comportamentos da borda web isolados (service mockado): 404 e a corrida de idempotência. */
@WebMvcTest(IngestionController.class)
class IngestionControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean IngestionService ingestionService;

  @Test
  void fonteDesconhecidaDeveRetornar404ComCorpoDeErroConsistente() throws Exception {
    mockMvc
        .perform(post("/ingestion/pix").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void corridaDeIdempotenciaDeveDevolver202ViaReplay() throws Exception {
    UUID ingestionId = UUID.randomUUID();
    when(ingestionService.ingest(eq(Source.GATEWAY), any(), eq("k1")))
        .thenThrow(new DataIntegrityViolationException("uk_idempotency_key"));
    when(ingestionService.replayByKey("k1"))
        .thenReturn(IngestionResult.accepted(ingestionId, UUID.randomUUID()));

    mockMvc
        .perform(
            post("/ingestion/gateway")
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.ingestionId").value(ingestionId.toString()));
  }
}
