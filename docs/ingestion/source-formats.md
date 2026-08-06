# Contratos de entrada da ingestão

Esta pasta é a **fonte da verdade dos formatos brutos** que o `ingestion-service` aceita via REST — o análogo de entrada do que `docs/events/` é para a saída (os eventos do RabbitMQ).

Cada fonte fala seu próprio idioma; um **adapter por fonte** normaliza o payload cru para o schema canônico (`TransactionNormalizedPayload`, ver [docs/events/transaction-normalized.md](../events/transaction-normalized.md)). Princípio: "ingestão burra, núcleo inteligente" — o ingestion só valida e normaliza.

## Endpoint

```
POST /ingestion/{source}
```

- `{source}` ∈ `gateway` | `bank-statement` | `internal-order` (mapeia para o enum `Source`). Valor fora disso → `404`.
- Corpo: JSON bruto no formato da fonte (abaixo).
- Header opcional `Idempotency-Key` (ver [Idempotência](#idempotência)).
- **`202 Accepted`** (com `ingestionId` e `traceId`) quando válido; **`400 Bad Request`** (com os erros) quando inválido. Ver [Validação e ciclo de vida](#validação-e-ciclo-de-vida).

## Fontes

### `GATEWAY` — webhook do provedor de pagamento

Valor em **centavos** (inteiro); carrega um id de transação próprio além do id da cobrança.

```json
{
  "chargeId": "chg_9f8e7d6c5b4a",
  "gatewayTxnId": "txn_abc123",
  "amountInCents": 19990,
  "currency": "BRL",
  "paidAt": "2026-07-29T13:45:00Z",
  "customerName": "Loja Exemplo LTDA",
  "paymentMethod": "credit_card"
}
```

| Campo bruto | Obrigatório | → Canônico | Normalização |
|---|---|---|---|
| `chargeId` | sim | `externalReference` | direto |
| `amountInCents` | sim | `amount` | centavos ÷ 100 → decimal string (`19990` → `"199.90"`) |
| `currency` | sim | `currency` | direto |
| `paidAt` | sim | `transactionDate` | parte de data do timestamp UTC |
| `customerName` | não | `counterparty` | direto |
| `gatewayTxnId` | não | `sourceMetadata.gatewayTxnId` | preservado |
| `paymentMethod` | não | `sourceMetadata.paymentMethod` | preservado |

### `BANK_STATEMENT` — linha de extrato bancário importada

Terso; **não carrega currency** (assume-se `BRL`); valor já decimal.

```json
{
  "reference": "chg_9f8e7d6c5b4a",
  "value": "199.90",
  "date": "2026-07-29",
  "description": "PAGAMENTO LOJA EXEMPLO"
}
```

| Campo bruto | Obrigatório | → Canônico | Normalização |
|---|---|---|---|
| `reference` | sim | `externalReference` | direto |
| `value` | sim | `amount` | direto (decimal string) |
| `date` | sim | `transactionDate` | direto (ISO date) |
| `description` | não | `counterparty` | direto |
| — | — | `currency` | default `BRL` (a fonte não fornece) |

### `INTERNAL_ORDER` — sistema interno de pedidos

Tem id de pedido próprio **e** a `externalReference` que liga à cobrança.

```json
{
  "orderId": "ORD-12345",
  "externalReference": "chg_9f8e7d6c5b4a",
  "totalAmount": "199.90",
  "currency": "BRL",
  "orderDate": "2026-07-29",
  "buyer": "Loja Exemplo LTDA"
}
```

| Campo bruto | Obrigatório | → Canônico | Normalização |
|---|---|---|---|
| `externalReference` | sim | `externalReference` | direto |
| `totalAmount` | sim | `amount` | direto (decimal string) |
| `currency` | sim | `currency` | direto |
| `orderDate` | sim | `transactionDate` | direto (ISO date) |
| `buyer` | não | `counterparty` | direto |
| `orderId` | sim | `sourceMetadata.orderId` | preservado |

## Validação e ciclo de vida

A validação é **síncrona** — acontece antes da resposta. O payload bruto é sempre persistido em `raw_ingestion` (auditoria), inclusive quando rejeitado.

| Estado (`raw_ingestion.status`) | Quando | Resposta |
|---|---|---|
| `RECEIVED` | estado inicial momentâneo (dentro da request) | — |
| `VALIDATED` | campos obrigatórios presentes, `amount` parseável, normalização ok | `202 Accepted` (`ingestionId`, `traceId`) + linha na `outbox` na mesma transação |
| `REJECTED` | falta campo obrigatório, `amount` inválido, etc. | `400 Bad Request` com `validation_errors`; **sem** publicar |

O `traceId` **nasce aqui** (UUID gerado por request) e é propagado no envelope do `TransactionNormalized`.

## Idempotência

Gateways reenviam webhooks — o mesmo POST pode chegar mais de uma vez. Para não gerar `TransactionNormalized` duplicado (e um `DUPLICATE` falso na conciliação), a ingestão aceita um header:

```
Idempotency-Key: <string única do cliente>
```

- Gravado em `raw_ingestion.idempotency_key` (`UNIQUE`).
- Reentrega com a **mesma** key → devolve o `ingestionId` já gravado, **sem** reprocessar nem republicar.
- Ausência da key → cada request é tratada como nova (best-effort).

Isso é idempotência na **borda**, complementando a dedup por `eventId` do consumidor (ADR-0002) — idempotência em dois níveis.
