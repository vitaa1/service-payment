package com.portfolio.reconciliation.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReconciliationStatusTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void deveDesserializarMatchedParaEnum() throws Exception {
    ReconciliationStatus status = mapper.readValue("\"MATCHED\"", ReconciliationStatus.class);
    assertEquals(ReconciliationStatus.MATCHED, status);
  }

  @Test
  void deveFalharAoDesserializarValorDesconhecido() {
    assertThrows(
        JsonMappingException.class,
        () -> mapper.readValue("\"CANCELLED\"", ReconciliationStatus.class));
  }
}
