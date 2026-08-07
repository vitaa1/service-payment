# Payment Reconciliation Engine

Motor de conciliação de pagamentos construído em **Java 21 + Spring Boot 3.x**, arquitetado como microsserviços desacoplados por mensageria. Projeto de portfólio focado em demonstrar boas práticas de arquitetura, testes e infraestrutura.

> **Status:** infraestrutura local pronta (docker-compose com os 4 serviços, bancos, RabbitMQ e Mailhog) e CI configurado. A lógica de negócio (conciliação, eventos, APIs) será implementada nas próximas fases.

## O que o sistema faz

Recebe registros de transações de fontes heterogêneas — webhook de gateway de pagamento, importação de extrato bancário e sistema interno de pedidos — e faz o *matching* entre elas, classificando cada caso como:

| Estado | Significado |
|---|---|
| `MATCHED` | Registro presente nas fontes esperadas com valores consistentes |
| `DIVERGENT` | Registro presente, mas com algum valor divergente (ex.: valor ou data) |
| `MISSING` | Registro esperado em uma fonte, mas ausente |
| `DUPLICATE` | Mesmo registro aparece mais de uma vez em uma fonte |

## Serviços

| Serviço | Responsabilidade | Entrada | Saída |
|---|---|---|---|
| **ingestion-service** | Valida e normaliza dados brutos para o schema canônico | REST (cliente) | `TransactionNormalized` |
| **reconciliation-service** | Núcleo: aplica o algoritmo de matching e persiste o resultado | `TransactionNormalized` | `ReconciliationCompleted`, `DivergenceDetected` |
| **notification-service** | Notifica divergências (e-mail) | `DivergenceDetected` | — |
| **report-service** | Consultas sobre o estado da conciliação (CQRS) | REST (cliente) + eventos | REST |

Módulo de apoio: **common-events** — contratos das mensagens trafegadas no RabbitMQ (envelope + payloads). Sem lógica de negócio.

## Stack

- **Runtime:** Java 21, Spring Boot 3.x
- **Mensageria:** RabbitMQ (comunicação assíncrona entre serviços)
- **Persistência:** PostgreSQL — um banco por serviço (*Database per Service*)
- **Testes:** JUnit 5 + Mockito (unitários), Testcontainers (integração, com Postgres e RabbitMQ reais)
- **Infra local:** Docker + docker-compose (serviços, bancos, RabbitMQ, Mailhog)
- **CI/CD:** GitHub Actions
- **Bônus:** Resilience4j, Spring Boot Actuator + Micrometer

## Documentação

- **[Arquitetura](docs/architecture.md)** — visão geral, diagramas, fluxo de eventos e modelagem de dados
- **[ADRs](docs/adr/README.md)** — registros de decisões de arquitetura
- **[Contratos de evento](docs/events/README.md)** — formato JSON das mensagens

## Estrutura do repositório

```
payment-reconciliation-engine/
├── pom.xml                     # POM pai (agregador + BOM de versões)
├── common-events/              # Contratos de evento compartilhados
├── ingestion-service/          # (cada serviço tem seu próprio Dockerfile)
├── reconciliation-service/
├── notification-service/
├── report-service/
├── docker-compose.yml          # infra local completa
├── .github/workflows/ci.yml    # pipeline de CI
└── docs/
    ├── architecture.md
    ├── adr/
    └── events/
```

## Como buildar

Requer Maven instalado localmente (ou use o Maven integrado da sua IDE, ex.: IntelliJ):

```bash
mvn clean verify
```

## Como subir a infraestrutura local

```bash
docker compose up --build
```

| Serviço | URL |
|---|---|
| ingestion-service | http://localhost:8081 |
| reconciliation-service | http://localhost:8082 |
| notification-service | http://localhost:8083 |
| report-service | http://localhost:8084 |
| RabbitMQ (painel) | http://localhost:15672 (guest/guest) |
| Mailhog (painel) | http://localhost:8025 |

## Possíveis evoluções futuras

Itens deliberadamente **fora do escopo atual** (o foco é entregar o fluxo de conciliação completo e bem testado). Ficam registrados aqui com a justificativa — a decisão consciente de escopo é parte do design.

### Observabilidade

Hoje há **health checks** via Actuator nos 4 serviços (`/actuator/health`, incluindo DB e RabbitMQ). Os demais pilares são evolução:

- **Métricas (Prometheus).** Adicionar `micrometer-registry-prometheus` e expor o endpoint `prometheus` (o Micrometer *core* já vem com o Actuator, mas não há registry nem endpoint exposto). Inclui métricas de negócio: contadores de `MATCHED`/`DIVERGENT`/`MISSING`/`DUPLICATE`, tamanho/latência da `outbox`, e o *poison-message* (`attempts >= max`).
- **Tracing distribuído (OpenTelemetry).** Plugar o **`traceId` que já nasce na ingestão e é propagado** nos eventos ao contexto de trace (Micrometer Tracing + OTel) e visualizar a transação ponta a ponta pelos 4 serviços num Jaeger/Tempo. A fundação (o `traceId`) já existe — falta conectá-la.
- **Logging estruturado.** Logs em JSON com o `traceId` no MDC, correlacionando logs e traces.
- **Stack local.** Prometheus + Grafana (+ Jaeger/Tempo) no `docker-compose` — hoje não há coletor/visualizador na infra local.

### Resiliência e operação

- **Circuit breaker (Resilience4j)** no consumo — já é dependência declarada; entra como bônus além do retry nativo do Spring AMQP (ADR-0010).
- **Rate limiting + limite de corpo** na borda pública de ingestão (ex.: Bucket4j).
- **CDC (Debezium)** como alternativa ao relay por *polling* do Outbox (ver [ADR-0006](docs/adr/0006-transactional-outbox.md)).

### Segurança / multi-tenant

- **Auth service / JWT** nas bordas (ingestion, report), e **idempotência escopada por cliente** (ver [ADR-0008](docs/adr/0008-idempotencia-da-ingestao.md)).

### Patterns avaliados e descartados (com motivo)

- **API Gateway** — só há 2 bordas REST (ingestion, report); um gateway aqui seria "teatro de microsserviços".
- **Saga** — o fluxo é coreografia por eventos, sem transação distribuída/compensação; forçar uma Saga seria *cargo-cult*.
- **Dashboard** — é frontend, fora do valor central (backend Java); o `report-service` já expõe as consultas via REST.

### notification-service — evoluções não implementadas nesta fatia

- **Roteamento de destinatário por `divergenceType`/severidade.** Hoje o `to` é único e estático (`NotificationProperties`); rotear por tipo de problema é aditivo e não muda o modelo de dedup (ADR-0011).
- **E-mail HTML/template.** O corpo hoje é texto puro (`SimpleMailMessage`); um template HTML trocaria só o `EmailComposer`, sem tocar no fluxo de envio/persistência.
- **Canais além de e-mail (SMS, Slack, etc.).** Exigiria extrair uma porta (`MailNotifier`/`NotificationChannel`) entre `NotificationService` e o envio concreto — não feito agora por YAGNI (só há um canal).
