# ADR-0004: CQRS parcimonioso no report-service

- **Status:** Aceito
- **Data:** 2026-07-29

## Contexto

O report-service precisa responder consultas do cliente sobre o estado da conciliação (ex.: "liste os casos `DIVERGENT` do dia", "qual o resumo por fonte"). Os dados que sustentam essas respostas nascem no reconciliation-service, mas — por [ADR-0003](0003-database-per-service.md) — o report **não pode** ler o banco da conciliação.

Restam duas formas de o report obter os dados: perguntar ao reconciliation em tempo de consulta (REST síncrono interno) ou manter sua própria cópia de leitura, alimentada por eventos. A primeira foi descartada em [ADR-0002](0002-comunicacao-hibrida-mensageria-rest.md) (sem REST interno entre serviços de processamento). Isso leva naturalmente a separar o modelo de **escrita** (que vive no reconciliation) do modelo de **leitura** (que vive no report) — o padrão CQRS.

## Decisão

Adotamos **CQRS de forma parcimoniosa**, restrito ao report-service:

- O **write model** é do reconciliation-service (tabelas normalizadas do estado da conciliação).
- O **read model** é uma **projeção desnormalizada** no `report_db` (`reconciliation_view`), otimizada para consulta e alimentada exclusivamente pelos eventos `ReconciliationCompleted` e `DivergenceDetected`.
- O report **só lê** sua projeção ao atender o cliente; nunca escreve por comando direto do cliente e nunca consulta outro serviço em tempo de request.

"Parcimonioso" é deliberado: **não** adotamos event sourcing, **não** separamos bancos de leitura/escrita dentro de um mesmo serviço, e **não** aplicamos CQRS nos demais serviços. Aplicamos o padrão apenas onde ele resolve um problema real.

## Alternativas consideradas

- **REST síncrono report → reconciliation em tempo de consulta.** Simples, sem projeção a manter, mas reacopla os serviços temporalmente (report cai se reconciliation cair), sobrecarrega o núcleo com carga de leitura e viola o [ADR-0002](0002-comunicacao-hibrida-mensageria-rest.md). Descartado.
- **Report consultando o banco da conciliação diretamente.** Viola frontalmente o Database per Service ([ADR-0003](0003-database-per-service.md)). Descartado.
- **Event sourcing completo.** Guardaria o histórico de eventos como fonte da verdade, permitindo replay e auditoria rica. Poderoso, mas complexo demais para o escopo — seria over-engineering para um portfólio de nível júnior.

## Consequências

### Positivas
- **Leitura desacoplada e rápida**: consultas batem numa tabela desenhada para elas, sem *joins* caros nem carga no núcleo.
- **Resiliência**: o report continua respondendo consultas mesmo se o reconciliation estiver fora do ar.
- Demonstra CQRS de forma justificada, sem cair em over-engineering.

### Negativas / custos
- **Consistência eventual**: a projeção fica alguns instantes atrás do write model. Aceitável para relatórios.
- **Eventos fora de ordem / reentrega**: RabbitMQ é *at-least-once* e não garante ordem global.
- Lógica de projeção a manter e testar.

### Mitigações
- **Idempotência** via tabela `processed_event` (ignora `eventId` já processado).
- **Ordenação por versão**: a projeção guarda `last_version` do caso e descarta eventos com versão menor, tolerando reentrega e reordenação (ver `reconciliation_case.version` em [architecture.md](../architecture.md)).
- A consistência eventual é comunicada na API (ex.: campo `updatedAt` na resposta) para não iludir o cliente sobre a atualidade do dado.
