# Evento: `DivergenceDetected`

Um caso foi avaliado e o resultado **não** é `MATCHED`. É emitido apenas quando há um problema a tratar, e dispara notificação. É consumido por dois serviços independentes.

| | |
|---|---|
| **eventType** | `DivergenceDetected` |
| **eventVersion** | 1 |
| **Produtor** | reconciliation-service |
| **Consumidor(es)** | notification-service, report-service |
| **Routing key** | `reconciliation.divergence.detected` |
| **Exchange** | `payments.events` |
| **Filas de destino** | `notification.divergence-detected.q`, `report.divergence-detected.q` |

## Payload

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `caseId` | UUID (string) | sim | Id do `reconciliation_case`. |
| `matchingKey` | string | sim | Chave do caso. |
| `divergenceType` | enum string | sim | `DIVERGENT` \| `MISSING` \| `DUPLICATE`. |
| `caseVersion` | inteiro | sim | Versão do caso (para ordenação no consumidor). |
| `externalReference` | string | sim | Referência comum da transação. |
| `details` | objeto | sim | Descrição estruturada da divergência (ver abaixo). |
| `detectedAt` | string ISO-8601 UTC | sim | Momento da detecção. |

### `details` por tipo

- **`DIVERGENT`** — campos que não conferem entre fontes:
  ```json
  { "field": "amount", "values": { "GATEWAY": "199.90", "BANK_STATEMENT": "189.90" } }
  ```
- **`MISSING`** — fontes esperadas ausentes:
  ```json
  { "expectedSources": ["GATEWAY","INTERNAL_ORDER"], "missingSources": ["INTERNAL_ORDER"] }
  ```
- **`DUPLICATE`** — fonte com registro repetido:
  ```json
  { "source": "GATEWAY", "occurrences": 2 }
  ```

## Exemplo

```json
{
  "eventId": "e9f0a1b2-c3d4-4e5f-9a0b-1c2d3e4f5a60",
  "eventType": "DivergenceDetected",
  "eventVersion": 1,
  "occurredAt": "2026-07-29T13:47:02.310Z",
  "traceId": "1f0c8a2e-77b4-4d51-9a3c-6e2b0d4f8a11",
  "producer": "reconciliation-service",
  "payload": {
    "caseId": "d1e2f3a4-b5c6-4a7e-8f90-1a2b3c4d5e6f",
    "matchingKey": "chg_9f8e7d6c5b4a|199.90|2026-07-29",
    "divergenceType": "DIVERGENT",
    "caseVersion": 2,
    "externalReference": "chg_9f8e7d6c5b4a",
    "details": {
      "field": "amount",
      "values": { "GATEWAY": "199.90", "BANK_STATEMENT": "189.90" }
    },
    "detectedAt": "2026-07-29T13:47:02.300Z"
  }
}
```

## Notas para os consumidores

**notification-service**
- **Idempotência:** deduplicar por `eventId` (`notification_log.event_id UNIQUE`) para não notificar duas vezes.
- Enviar e-mail (Mailhog no ambiente local) e registrar em `notification_log`.
- Falha de envio → registrar `FAILED`; retry/DLQ conforme regras em [README](README.md).

**report-service**
- **Idempotência** via `processed_event`; **ordenação** via `caseVersion`.
- Atualiza contadores/detalhes de divergência na projeção `reconciliation_view`.

> `DivergenceDetected` acompanha um `ReconciliationCompleted` de mesmo `caseVersion` (o report recebe ambos). O `ReconciliationCompleted` define o estado; o `DivergenceDetected` detalha o problema e aciona a notificação.
