# ADR-0005: Design de serialização e tipagem do common-events

- **Status:** Aceito
- **Data:** 2026-08-05

## Contexto

O módulo `common-events` ([ADR-0003](0003-database-per-service.md)) materializa em Java os contratos descritos em `docs/events/` (a fonte da verdade). Falta decidir **como** os records mapeiam o formato de fio: tipos dos campos, estratégia de (des)serialização do envelope genérico, modelagem de payloads que variam de forma, tratamento de valores desconhecidos e onde vivem as constantes de roteamento. São decisões de baixo nível, mas com efeito direto sobre segurança de desserialização, acoplamento e a capacidade de o contrato "falhar cedo" — relevante num domínio financeiro.

O módulo só pode depender de `jackson-annotations` (sem Spring), por força do escopo estrito do ADR-0003.

## Decisão

1. **Tipos ricos.** Os records usam tipos de domínio, não `String` cru: `BigDecimal` para `amount` (com `@JsonFormat(shape = STRING)` para preservar a string decimal `"199.90"` no fio), `LocalDate` para datas, `Instant` para timestamps, `enum` para `source`/`status`/`divergenceType`.

2. **Envelope genérico, desserializado por tipo concreto.** `EventEnvelope<T extends EventPayload>`. Cada consumidor desserializa para o tipo concreto que sua fila carrega (uma fila = um `eventType`), inferido pela assinatura do `@RabbitListener`. **Sem** `@JsonTypeInfo`/default typing no envelope.

3. **`DivergenceDetails` como interface selada com allowlist.** O campo `details` do `DivergenceDetected` é um tipo-união fechado (`DivergentDetails` | `MissingDetails` | `DuplicateDetails`), desserializado polimorficamente via `@JsonSubTypes` (allowlist explícito) discriminado por `divergenceType` (`EXTERNAL_PROPERTY`). Blindado por testes de round-trip.

4. **Enums estritos e separados.** `Source`, `ReconciliationStatus` e `DivergenceType` são enums distintos (um `DivergenceType` nunca é `MATCHED` — a regra vive no tipo). Valor desconhecido na desserialização **falha** (mensagem → DLQ), não é mapeado para um `UNKNOWN`.

5. **Constantes de roteamento restritas ao contrato compartilhado.** `common-events` guarda só `Exchanges`, `RoutingKeys` e `EventTypes` (classes `final` de constantes). Nomes de **fila/DLQ** ficam em cada serviço consumidor, que declara a própria fila fazendo binding na exchange + routing key compartilhadas.

6. **Layout por tipo.** Raiz `com.portfolio.reconciliation.events`, com `payload/` (e `payload/divergence/`) e `routing/`.

## Alternativas consideradas

- **Contrato de fio cru (tudo `String`).** Mais tolerante, mas empurra conversão/validação para cada serviço e não honra estruturalmente a regra "nunca `double`". Descartado.
- **Payload polimórfico no envelope (`@JsonTypeInfo`).** Desnecessário: a topologia já garante um `eventType` por fila. Adicionaria superfície de desserialização insegura sem ganho. Descartado.
- **`details` como `Map<String,Object>`/`JsonNode`.** Simples, mas perde type-safety num campo que os consumidores interpretam. Mantido apenas como fallback; preferimos a interface selada.
- **Enums lenientes (`@JsonEnumDefaultValue`).** Toleraria valores novos, mas engoliria silenciosamente contrato quebrado — inaceitável em dado financeiro. Descartado.

## Consequências

### Positivas
- Contrato **auto-documentado e type-safe**; a regra "amount é `BigDecimal`, nunca `double`" é imposta pelo tipo.
- **Sem desserialização polimórfica insegura** no envelope (satisfaz o `security-guard`); o único polimorfismo é um allowlist fechado e testado.
- `common-events` permanece mínimo e desacoplado da infra de quem consome (um consumidor novo não toca no módulo).

### Negativas / custos
- Valor de enum novo é mudança **incompatível** (acopla deploy de produtor/consumidor) — opção deliberada de falhar cedo.
- `EXTERNAL_PROPERTY` do Jackson é sensível de configurar com records.
- Cada consumidor declara o tipo concreto que espera (boilerplate explícito).

### Mitigações
- **Testes de round-trip + golden JSON** (cópias dos exemplos de `docs/events/` em `test/resources`) travam o formato de fio no CI.
- O agente **`event-contract-checker`** guarda, em review, a sincronia entre os records, as cópias golden e `docs/events/`.
- Valor de enum novo é tratado como versionamento de contrato explícito (ver [ADR-0003](0003-database-per-service.md)).
