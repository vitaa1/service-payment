package com.portfolio.reconciliation.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DivergenceTypeTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void deveDesserializarDivergentParaEnum() throws Exception {
    DivergenceType type = mapper.readValue("\"DIVERGENT\"", DivergenceType.class);
    assertEquals(DivergenceType.DIVERGENT, type);
  }

  @Test
  void deveFalharAoDesserializarMatched() {
    // MATCHED não é um problema — não pertence a DivergenceType (só a ReconciliationStatus).
    assertThrows(
        JsonMappingException.class, () -> mapper.readValue("\"MATCHED\"", DivergenceType.class));
  }
}
