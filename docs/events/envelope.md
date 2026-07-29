# Envelope de evento

Todo evento publicado no `payments.events` usa o mesmo envelope. O envelope carrega **metadados de mensageria** (identidade, rastreabilidade, versão); o dado de negócio fica em `payload`.

## Estrutura

```json
{
  "eventId": "b4f1e6a0-9c2d-4a7e-8f13-2a6d5c1e9b00",
  "eventType": "TransactionNormalized",
  "eventVersion": 1,
  "occurredAt": "2026-07-29T13:45:12.482Z",
  "traceId": "1f0c8a2e-77b4-4d51-9a3c-6e2b0d4f8a11",
  "producer": "ingestion-service",
  "payload": {
    "...": "específico de cada evento"
  }
}
```

## Campos

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `eventId` | UUID (string) | sim | Identificador único **desta mensagem**. Base da idempotência: o consumidor deduplica por ele. |
| `eventType` | string | sim | Nome do evento (`TransactionNormalized`, `ReconciliationCompleted`, `DivergenceDetected`). |
| `eventVersion` | inteiro | sim | Versão do contrato do payload. Começa em `1`. Incrementa em mudanças incompatíveis. |
| `occurredAt` | string ISO-8601 UTC | sim | Momento em que o fato ocorreu (não o de publicação). Usado para ordenação temporal. |
| `traceId` | UUID (string) | sim | Correlaciona todos os eventos derivados de uma mesma transação. Nasce no ingestion e é propagado. |
| `producer` | string | sim | Serviço que publicou o evento. Útil para depuração. Não confundir com `payload.source` (a origem **do dado**: gateway/banco/pedido). |
| `payload` | objeto | sim | Conteúdo de negócio, definido no documento de cada evento. |

## Notas de projeto

- **`eventId` vs `traceId`.** `eventId` é único por mensagem; `traceId` é compartilhado por todas as mensagens de uma mesma transação ao longo do pipeline. Deduplicação usa `eventId`; rastreamento ponta a ponta usa `traceId`.
- **`producer` vs `payload.source`.** `producer` é o *serviço* emissor (ex.: `ingestion-service`). `payload.source` é a *fonte de dados* de negócio (`GATEWAY`, `BANK_STATEMENT`, `INTERNAL_ORDER`). São conceitos diferentes e ambos existem de propósito.
- **`occurredAt` em UTC.** Sempre UTC com sufixo `Z`, para evitar ambiguidade de fuso.

## Representação em `common-events` (referência)

Assinatura pretendida (a ser implementada na próxima fase; aqui só para fixar o contrato):

```
record EventEnvelope<T>(
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    UUID traceId,
    String producer,
    T payload
)
```
