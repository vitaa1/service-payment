# ADR-0008: Idempotência da ingestão via Idempotency-Key

- **Status:** Aceito
- **Data:** 2026-08-06

## Contexto

A borda de ingestão recebe dados de fontes que **reentregam**: gateways de pagamento reenviam webhooks após timeout, importações podem ser reexecutadas. Sem defesa, o mesmo POST processado duas vezes gera **duas linhas em `raw_ingestion` e dois eventos `TransactionNormalized`** para a mesma perna real da transação.

A idempotência do consumidor (dedup por `eventId`, ADR-0002) **não** resolve isso: cada request de ingestão gera um `eventId` novo, então os dois eventos são distintos para o `reconciliation-service` — que pode então marcar um **`DUPLICATE` falso** (mesma fonte, mesma matching key, duas vezes). É preciso deduplicar na própria borda, antes de o evento nascer.

## Decisão

A ingestão aceita um header `Idempotency-Key` (estilo Stripe), opcional:

1. A key é persistida em `raw_ingestion.idempotency_key` com constraint `UNIQUE`.
2. Uma request cuja key **já existe** retorna o `ingestionId` originalmente gravado, **sem** reprocessar nem gravar nova linha na `outbox` — logo, sem republicar.
3. Requests **sem** a key são tratadas como novas (best-effort — o cliente que não envia a key abre mão da garantia).

Isso separa de forma inequívoca **reentrega** (mesma key) de **duplicata genuína** (keys diferentes, mesmos dados de negócio — que é justamente o que a conciliação deve detectar como `DUPLICATE`).

## Alternativas consideradas

- **Chave natural derivada (`source` + id de entrega).** Rejeitada: os formatos de entrada não carregam um id de *entrega* distinto do id de *transação* (o `chargeId` do gateway identifica a cobrança, não a entrega do webhook). Duas entregas legítimas do mesmo charge colidiriam, apagando duplicatas reais.
- **Nenhuma idempotência na borda.** Rejeitada: deixaria o sistema sujeito a `DUPLICATE` falso a cada retry de webhook — um cenário comum, não excepcional.

## Consequências

### Positivas
- Idempotência em **dois níveis** (borda via `Idempotency-Key`, consumo via `eventId`) — defesa em profundidade contra reentrega.
- Padrão de indústria (Stripe/PayPal), familiar a integradores.
- Preserva a semântica de `DUPLICATE` da conciliação (só duplicatas genuínas chegam lá).

### Negativas / custos
- Coluna `UNIQUE` adicional e um lookup por request.
- A garantia depende de o **cliente** enviar a key; sem ela, não há proteção (opção deliberada — não temos um id de entrega confiável para impor).
- A idempotência **não é escopada por cliente** (o endpoint é público, sem autenticação nesta fase). Um chamador que reuse a key de outro recebe o `ingestionId`/`traceId` associado (baixa sensibilidade). Escopar por cliente fica para quando houver auth.

### Mitigações
- Documentado no contrato de entrada ([docs/ingestion/source-formats.md](../ingestion/source-formats.md)) que a key é a forma suportada de tornar a entrega idempotente.
- A constraint `UNIQUE` no banco é a garantia final (uma corrida de duas requests com a mesma key resulta em uma inserção e uma violação tratada como replay).
