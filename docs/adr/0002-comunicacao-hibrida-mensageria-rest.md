# ADR-0002: Comunicação híbrida — mensageria entre serviços, REST na borda

- **Status:** Aceito
- **Data:** 2026-07-29

## Contexto

Definida a arquitetura de microsserviços ([ADR-0001](0001-adocao-de-microsservicos.md)), é preciso decidir *como* os serviços conversam. Há dois tipos de interação bem diferentes no sistema:

1. **Interações internas de processamento** (ingestion → reconciliation → notification/report): fazem parte de um pipeline. O produtor não precisa de resposta imediata e não deveria depender da disponibilidade do consumidor.
2. **Interações de borda** (cliente → ingestion para enviar dados; cliente → report para consultar): um cliente externo espera uma resposta HTTP síncrona, com semântica de request/response bem definida.

Acoplar os serviços de processamento por REST síncrono criaria uma cadeia frágil: se a conciliação estivesse fora do ar, a ingestão falharia; se a notificação caísse, a conciliação travaria. Também dificultaria adicionar novos consumidores de um mesmo fato.

## Decisão

Adotamos **comunicação híbrida**:

- **Mensageria assíncrona (RabbitMQ)** para toda comunicação entre serviços de processamento. Os serviços publicam **eventos de fato ocorrido** (ex.: `TransactionNormalized`) e consomem o que lhes interessa. Não há chamada REST interna entre serviços de processamento.
- **REST síncrono apenas na borda**: cliente → `ingestion-service` (enviar dados brutos) e cliente → `report-service` (consultar estado).

## Alternativas consideradas

- **REST síncrono em tudo (inclusive interno).** Simples de entender e depurar, mas acopla temporalmente os serviços (todos precisam estar no ar ao mesmo tempo), dificulta múltiplos consumidores e não demonstra mensageria — um dos objetivos do projeto.
- **Mensageria em tudo (inclusive borda).** Forçaria o cliente externo a um modelo assíncrono (webhooks/polling) mesmo para uma simples consulta, o que é uma péssima experiência de API para leitura.
- **Event streaming (Kafka).** Poderoso para *event sourcing* e replay, mas é peso excessivo para o volume deste projeto; RabbitMQ cobre roteamento, DLQ e *at-least-once* com muito menos cerimônia.

## Consequências

### Positivas
- **Desacoplamento temporal**: um serviço de processamento pode estar fora do ar sem quebrar o produtor; as mensagens esperam na fila.
- **Extensibilidade**: adicionar um consumidor novo de `DivergenceDetected` não exige mudança no produtor (report e notification já compartilham esse evento).
- **Borda ergonômica**: clientes externos usam HTTP convencional, com status codes e validação síncrona.
- Demonstra explicitamente uso de broker de mensagens.

### Negativas / custos
- **Entrega at-least-once**: a mesma mensagem pode ser entregue mais de uma vez.
- **Consistência eventual**: a projeção do report reflete o estado com um pequeno atraso.
- Dois modelos de comunicação para manter e testar.

### Mitigações
- **Idempotência obrigatória** em todo consumidor, deduplicando por `eventId` (tabelas `processed_event` / colunas `event_id UNIQUE`).
- **Envelope com `traceId` e `occurredAt`** em todas as mensagens, para rastreabilidade e ordenação. Contratos em [docs/events](../events/README.md).
- **Dead Letter Queue (DLQ)** por fila para mensagens que falham repetidamente, evitando *poison messages* em loop.
- Resposta **202 Accepted** na ingestão deixa explícito ao cliente que o processamento é assíncrono.
