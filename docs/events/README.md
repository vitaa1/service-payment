# Contratos de evento

Esta pasta é a **fonte da verdade** dos eventos que trafegam no RabbitMQ. O módulo `common-events` deve refletir exatamente estes contratos.

Todos os eventos compartilham um [envelope comum](envelope.md); cada documento de evento descreve apenas o `payload` específico.

## Catálogo de eventos

| Evento | Produtor | Consumidor(es) | Documento |
|---|---|---|---|
| `TransactionNormalized` | ingestion-service | reconciliation-service | [transaction-normalized.md](transaction-normalized.md) |
| `ReconciliationCompleted` | reconciliation-service | report-service | [reconciliation-completed.md](reconciliation-completed.md) |
| `DivergenceDetected` | reconciliation-service | notification-service, report-service | [divergence-detected.md](divergence-detected.md) |

## Topologia RabbitMQ

Um único **topic exchange** roteia todos os eventos de domínio. As *routing keys* nomeiam o fato ocorrido; as filas fazem *binding* no que lhes interessa.

```mermaid
flowchart LR
    ING[ingestion-service] -- transaction.normalized --> X{{payments.events\n(topic)}}
    REC[reconciliation-service] -- reconciliation.completed --> X
    REC -- reconciliation.divergence.detected --> X

    X -- transaction.normalized --> Q1[[reconciliation.transaction-normalized.q]]
    X -- reconciliation.completed --> Q2[[report.reconciliation-completed.q]]
    X -- reconciliation.divergence.detected --> Q3[[report.divergence-detected.q]]
    X -- reconciliation.divergence.detected --> Q4[[notification.divergence-detected.q]]

    Q1 --> REC
    Q2 --> REP[report-service]
    Q3 --> REP
    Q4 --> NOT[notification-service]
```

### Exchange

| Nome | Tipo | Durável | Observação |
|---|---|---|---|
| `payments.events` | topic | sim | todos os eventos de domínio |
| `payments.events.dlx` | topic | sim | dead-letter exchange |

### Routing keys

| Routing key | Evento |
|---|---|
| `transaction.normalized` | `TransactionNormalized` |
| `reconciliation.completed` | `ReconciliationCompleted` |
| `reconciliation.divergence.detected` | `DivergenceDetected` |

### Filas e bindings

| Fila | Binding (routing key) | Serviço consumidor | DLQ |
|---|---|---|---|
| `reconciliation.transaction-normalized.q` | `transaction.normalized` | reconciliation | `reconciliation.transaction-normalized.q.dlq` |
| `report.reconciliation-completed.q` | `reconciliation.completed` | report | `report.reconciliation-completed.q.dlq` |
| `report.divergence-detected.q` | `reconciliation.divergence.detected` | report | `report.divergence-detected.q.dlq` |
| `notification.divergence-detected.q` | `reconciliation.divergence.detected` | notification | `notification.divergence-detected.q.dlq` |

Note que `DivergenceDetected` alimenta **duas filas** (report e notification): o mesmo fato é consumido por dois serviços de forma totalmente independente — exemplo prático do desacoplamento por evento.

## Regras transversais

- **Entrega at-least-once.** Todo consumidor **deve** deduplicar por `eventId`. A mesma mensagem pode chegar mais de uma vez.
- **Ordem não garantida.** Não assuma ordem entre mensagens. Para o estado de um caso, use `payload.caseVersion` para descartar atualizações antigas.
- **Dead Letter Queue.** Cada fila declara `x-dead-letter-exchange: payments.events.dlx`. Mensagens que estouram o limite de retry vão para a `.dlq` correspondente, evitando *poison messages* em loop.
- **Retry.** Recomenda-se retry com backoff no consumidor (Resilience4j) antes de encaminhar à DLQ.
- **Serialização.** JSON UTF-8. Campos desconhecidos devem ser ignorados na desserialização (tolerância a evolução aditiva do contrato).
- **Compatibilidade.** Mudanças aditivas (campos novos opcionais) mantêm `eventVersion`. Mudanças incompatíveis incrementam `eventVersion` ou criam um novo `eventType`.
