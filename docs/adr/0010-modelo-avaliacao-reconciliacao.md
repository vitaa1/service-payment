# ADR-0010: Modelo de avaliação e concorrência da reconciliação

- **Status:** Aceito
- **Data:** 2026-08-06

## Contexto

O `reconciliation-service` é o núcleo do sistema. Ele consome `TransactionNormalized` (uma perna, de uma fonte), agrupa por matching key ([ADR-0009](0009-matching-key-external-reference.md)) num `reconciliation_case`, e o (re)avalia a cada nova perna. Este ADR fixa **como** ele avalia e **como** processa com segurança sob concorrência e entrega at-least-once. Os contratos de evento estão em `docs/events/`.

## Decisão

### Avaliação do caso

1. **Fontes esperadas** (política configurável, `reconciliation.expected-sources`): default `{GATEWAY, INTERNAL_ORDER}`. O `BANK_STATEMENT` agrega confiança quando presente, mas sua ausência **não** gera `MISSING`.

2. **`MATCHED`** = todas as fontes **esperadas presentes** E todos os registros **presentes** mutuamente consistentes. A presença exigida é só das esperadas; a consistência é checada entre *todas* as presentes (inclusive `BANK_STATEMENT`).

3. **Comparação de consistência:** `amount` via `BigDecimal.compareTo` (value-based, insensível a escala; nunca `equals`/`double`); `currency` e `transactionDate` por igualdade exata.

4. **Precedência** quando vários problemas coexistem: **`DUPLICATE` > `DIVERGENT` > `MISSING` > `MATCHED`**. Duplicata é integridade na origem (mais grave); divergência entre presentes é um conflito estável; `MISSING` é frequentemente transitório (pernas chegam em momentos diferentes). Reportar `DIVERGENT` antes de `MISSING` surface o conflito mais cedo.

5. **`DivergenceDetected.details` (tipo `DIVERGENT`)** reporta **um** campo — precedência `amount` > `currency` > `transactionDate` — com o `values` por fonte presente.

6. **`DUPLICATE`** = mais de um `normalized_record` com o mesmo `case_id` + `source` (deriva do `normalized_record`; a tabela `case_member` do `§6.2` é dispensada).

### Concorrência e idempotência

7. **Dedup por `eventId`:** grava-se o `normalized_record`; violação de `event_id UNIQUE` = reentrega → a reavaliação é pulada (ADR-0002).

8. **Lock pessimista no caso:** `SELECT ... FOR UPDATE` na linha do `reconciliation_case` (por `matching_key`) serializa as reavaliações **do mesmo caso**, deixando casos distintos em paralelo. O race de criação é resolvido pelo `matching_key UNIQUE` + re-fetch com lock.

9. **`caseVersion`:** o `reconciliation_case.version` começa em `1` e incrementa a cada reavaliação não-duplicada; os eventos carregam a versão pós-incremento. Como as reavaliações do mesmo caso são serializadas (8), a versão cresce monotonicamente. Emite-se a cada chegada (ADR-0006 via Outbox), mesmo sem mudança de status.

### `traceId` vs `caseId`

10. Cada fonte é uma **ingestão separada, com `traceId` próprio**. O evento emitido carrega o **`traceId` da `TransactionNormalized` que disparou aquela reavaliação** (rastreia a cadeia daquela perna). O **`caseId` é o correlacionador do caso** ao longo das pernas. Não se força um `traceId` único num caso que nasce de várias ingestões.

## Alternativas consideradas

- **Lock otimista (`@Version`) + retry** ou **consumidor single-thread** (8): descartados; o pessimista serializa por caso sem limitar o throughput global e sem retry.
- **`MISSING` antes de `DIVERGENT`** (4): esconderia o conflito atrás da incompletude.
- **Retry via Resilience4j** no consumo: o retry com backoff nativo do Spring AMQP já cobre isso e integra com a DLQ; o Resilience4j fica como *circuit breaker* bônus futuro.
- **Extrair um módulo de Outbox compartilhado:** com 2 produtores (ingestion, reconciliation), duplicar é a escolha (rule of three) — evita abstração prematura e preserva autonomia (database-per-service).

## Consequências

### Positivas
- Avaliação correta e determinística; concorrência segura por caso; ordenação garantida no consumidor via `caseVersion`.
- Schema mais enxuto (sem `case_member`).

### Negativas / custos
- O lock pessimista segura a linha do caso durante a transação de reavaliação.
- Duplicação do código de Outbox entre ingestion e reconciliation (drift a vigiar).
- Um caso incompleto **e** divergente é reportado como `DIVERGENT` — a completude fica visível pelos `sourcesPresent`.

### Mitigações
- Reavaliação é uma transação curta (sem I/O de rede — a publicação é via Outbox/relay assíncrono).
- Testes: TDD unitário para o avaliador (o núcleo) e Testcontainers para persistência/concorrência/mensageria.
