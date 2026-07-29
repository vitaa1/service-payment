# Evento: `TransactionNormalized`

Um registro de transação, vindo de **uma** fonte, foi validado e normalizado para o schema canônico pelo ingestion-service. É o pontapé do pipeline de conciliação.

| | |
|---|---|
| **eventType** | `TransactionNormalized` |
| **eventVersion** | 1 |
| **Produtor** | ingestion-service |
| **Consumidor(es)** | reconciliation-service |
| **Routing key** | `transaction.normalized` |
| **Exchange** | `payments.events` |
| **Fila de destino** | `reconciliation.transaction-normalized.q` |

## Payload

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `ingestionId` | UUID (string) | sim | Id do registro em `ingestion_db.raw_ingestion` (auditoria/rastreio). |
| `source` | enum string | sim | Origem do dado: `GATEWAY` \| `BANK_STATEMENT` \| `INTERNAL_ORDER`. |
| `externalReference` | string | sim | Identificador comum da transação entre as fontes (ex.: id da cobrança no gateway). Base da matching key. |
| `amount` | string decimal | sim | Valor da transação. String decimal para evitar erro de ponto flutuante (ex.: `"199.90"`). |
| `currency` | string (ISO 4217) | sim | Moeda, ex.: `"BRL"`. |
| `transactionDate` | string (ISO date) | sim | Data da transação, `YYYY-MM-DD`. |
| `counterparty` | string | não | Contraparte/descrição, quando a fonte fornece. |
| `sourceMetadata` | objeto | não | Campos extras específicos da fonte, preservados para auditoria. |

## Exemplo

```json
{
  "eventId": "b4f1e6a0-9c2d-4a7e-8f13-2a6d5c1e9b00",
  "eventType": "TransactionNormalized",
  "eventVersion": 1,
  "occurredAt": "2026-07-29T13:45:12.482Z",
  "traceId": "1f0c8a2e-77b4-4d51-9a3c-6e2b0d4f8a11",
  "producer": "ingestion-service",
  "payload": {
    "ingestionId": "8a2b1c3d-4e5f-6071-8293-a4b5c6d7e8f9",
    "source": "GATEWAY",
    "externalReference": "chg_9f8e7d6c5b4a",
    "amount": "199.90",
    "currency": "BRL",
    "transactionDate": "2026-07-29",
    "counterparty": "Loja Exemplo LTDA",
    "sourceMetadata": {
      "gatewayTxnId": "txn_abc123",
      "paymentMethod": "credit_card"
    }
  }
}
```

## Notas para o consumidor (reconciliation-service)

- **Idempotência:** deduplicar por `eventId` (`normalized_record.event_id UNIQUE`).
- **Matching key:** derivada de `externalReference | amount | transactionDate` (ver [architecture.md §5](../architecture.md)).
- Cada `TransactionNormalized` representa **uma** perna da transação (uma fonte). O caso só fica `MATCHED` quando as fontes esperadas chegam e conferem — pernas chegam em momentos diferentes.
- `amount` chega como string decimal; converter para `BigDecimal` (nunca `double`) ao comparar.
