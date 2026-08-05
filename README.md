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
