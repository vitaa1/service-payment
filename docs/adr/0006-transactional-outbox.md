# ADR-0006: Publicação de eventos via Transactional Outbox

- **Status:** Aceito
- **Data:** 2026-08-05

## Contexto

Os serviços produtores (`ingestion`, `reconciliation`) precisam, para cada fato de negócio, (1) commitar estado no seu banco e (2) publicar um evento no RabbitMQ. Feitas como duas operações independentes, não há atomicidade: um crash entre elas gera **evento perdido** (banco commitou, publish falhou) ou **evento fantasma** (publicou, banco deu rollback). Num pipeline de conciliação financeira, ambos são inaceitáveis.

O RabbitMQ entrega at-least-once e os consumidores já deduplicam por `eventId` ([docs/events](../events/README.md)) — logo, **duplicatas** são toleráveis, mas **perdas** não.

## Decisão

Adotamos o **Transactional Outbox** nos serviços produtores:

1. **Escrita atômica.** O evento é gravado numa tabela `outbox` **na mesma transação** do estado de negócio. Ou ambos persistem, ou nenhum.
2. **Relay por polling.** Um `@Scheduled` no próprio serviço lê linhas pendentes (`published_at IS NULL AND attempts < MAX`) com `SELECT ... FOR UPDATE SKIP LOCKED ORDER BY id`, publica no RabbitMQ e marca `published_at` **somente após o confirm do broker** (publisher confirms).
3. **Schema.** `outbox(id BIGSERIAL PK, event_id UUID UNIQUE, event_type, routing_key, trace_id, payload JSONB /* envelope completo serializado */, created_at, published_at NULL, attempts)`. Uma tabela por serviço produtor (database-per-service).
4. **Falha e envenenamento.** Falha de publish incrementa `attempts` e re-tenta no próximo ciclo; ao atingir `MAX`, a linha é ignorada e exposta por métrica/log para investigação (equivalente a uma DLQ do lado da outbox).
5. **Retenção.** Linhas publicadas são mantidas (rastro de auditoria) e removidas por um purge agendado após uma janela curta.

## Alternativas consideradas

- **Publish direto (sem outbox).** Simples, mas é exatamente o dual-write sujeito a perda/fantasma. Descartado.
- **CDC com Debezium.** Elimina o polling lendo o WAL do Postgres, quase em tempo real. Exige Debezium + Kafka Connect — infra pesada e Kafka num projeto RabbitMQ. Registrado como evolução futura, não adotado agora.
- **Publish imediato pós-commit (`afterCommit`) com outbox de fallback.** Menor latência, mas o publish imediato ainda pode falhar e recai no polling — mais complexidade para ganho marginal. Descartado.

## Consequências

### Positivas
- **Sem perda de evento**: a publicação é garantida por uma linha durável, committada junto do estado.
- **Zero infra extra** (sem Kafka/Debezium) — encaixa no docker-compose atual.
- Concorrência entre instâncias resolvida com `SKIP LOCKED`; ordem de publicação aproximada por `id`.

### Negativas / custos
- **Latência de polling** (sub-segundo, ajustável) e carga constante pequena no banco.
- **Duplicatas** possíveis (crash entre publish e marcação) — empurradas para a idempotência do consumidor.
- Mais duas tabelas de trabalho e dois `@Scheduled` (relay + purge) por serviço produtor.
- **O relay segura os locks (`FOR UPDATE`) das linhas durante o publish síncrono** (espera do confirm). Com broker lento, a transação — e a conexão do pool — fica aberta por até `batch-size × confirm-timeout`. Mitigado com `batch-size` pequeno; um refinamento futuro é separar "reivindicar" (transação curta) de "publicar" (fora de transação), atualizando `published_at` numa segunda transação.
- **Poison message do lado produtor:** ao esgotar `max-attempts`, a linha deixa de ser selecionada e hoje só há `log.warn`. Uma métrica/alerta sobre `attempts >= max-attempts AND published_at IS NULL` fica como evolução (fase de observabilidade).

### Mitigações
- Consumidores **deduplicam por `eventId`** (regra transversal já existente), absorvendo as duplicatas.
- **Publisher confirms** garantem que só se marca como publicado o que o broker aceitou.
- **Testes de integração com Testcontainers** (Postgres + RabbitMQ) cobrem o relay, o `SKIP LOCKED` e o comportamento sob falha.
