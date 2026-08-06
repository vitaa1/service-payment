package com.portfolio.reconciliation.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SourceTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void deveDesserializarGatewayParaEnum() throws Exception {
    Source source = mapper.readValue("\"GATEWAY\"", Source.class);
    assertEquals(Source.GATEWAY, source);
  }

  @Test
  void deveFalharAoDesserializarValorDesconhecido() {
    assertThrows(JsonMappingException.class, () -> mapper.readValue("\"CRYPTO_WALLET\"", Source.class));
  }
}
