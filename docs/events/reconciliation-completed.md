# Evento: `ReconciliationCompleted`

Um caso de conciliação foi (re)avaliado e tem um estado atual. É emitido para **todo** resultado — inclusive `MATCHED` — porque alimenta a projeção de leitura do report-service.

| | |
|---|---|
| **eventType** | `ReconciliationCompleted` |
| **eventVersion** | 1 |
| **Produtor** | reconciliation-service |
| **Consumidor(es)** | report-service |
| **Routing key** | `reconciliation.completed` |
| **Exchange** | `payments.events` |
| **Fila de destino** | `report.reconciliation-completed.q` |

## Payload

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `caseId` | UUID (string) | sim | Id do `reconciliation_case`. |
| `matchingKey` | string | sim | Chave que agrupou os registros do caso. |
| `status` | enum string | sim | `MATCHED` \| `DIVERGENT` \| `MISSING` \| `DUPLICATE`. |
| `caseVersion` | inteiro | sim | Versão do caso. Cresce a cada reavaliação; o consumidor descarta versões antigas. |
| `externalReference` | string | sim | Referência comum da transação. |
| `amount` | string decimal | não | Valor consolidado do caso (quando aplicável). |
| `currency` | string (ISO 4217) | não | Moeda. |
| `transactionDate` | string (ISO date) | não | Data da transação. |
| `sourcesPresent` | array de enum | sim | Fontes já vistas no caso, ex.: `["GATEWAY","INTERNAL_ORDER"]`. |
| `evaluatedAt` | string ISO-8601 UTC | sim | Momento da avaliação. |

## Exemplo

```json
{
  "eventId": "c7d2f3a1-0b4e-4c9d-a1f2-3e4d5c6b7a80",
  "eventType": "ReconciliationCompleted",
  "eventVersion": 1,
  "occurredAt": "2026-07-29T13:45:15.007Z",
  "traceId": "1f0c8a2e-77b4-4d51-9a3c-6e2b0d4f8a11",
  "producer": "reconciliation-service",
  "payload": {
    "caseId": "d1e2f3a4-b5c6-4a7e-8f90-1a2b3c4d5e6f",
    "matchingKey": "chg_9f8e7d6c5b4a|199.90|2026-07-29",
    "status": "MATCHED",
    "caseVersion": 3,
    "externalReference": "chg_9f8e7d6c5b4a",
    "amount": "199.90",
    "currency": "BRL",
    "transactionDate": "2026-07-29",
    "sourcesPresent": ["GATEWAY", "BANK_STATEMENT", "INTERNAL_ORDER"],
    "evaluatedAt": "2026-07-29T13:45:15.001Z"
  }
}
```

## Notas para o consumidor (report-service)

- **Idempotência:** registrar `eventId` em `processed_event`; ignorar reprocessamento.
- **Ordenação:** aplicar à projeção apenas se `caseVersion` > `reconciliation_view.last_version` para o `caseId`. Caso contrário, descartar (evento fora de ordem/reentregue).
- Este evento é a base do read model; `DivergenceDetected` apenas complementa contadores de divergência.
