# Payment Reconciliation Engine

Motor de conciliação de pagamentos construído em **Java 21 + Spring Boot 3.x**, arquitetado como microsserviços desacoplados por mensageria. Projeto de portfólio focado em demonstrar boas práticas de arquitetura, testes e infraestrutura.

> **Status:** fase de design. Este repositório contém, por enquanto, a estrutura multi-módulo e a documentação de arquitetura. O código de aplicação será implementado nas próximas fases.

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
├── ingestion-service/
├── reconciliation-service/
├── notification-service/
├── report-service/
├── docker/                     # Dockerfiles por serviço (fase de implementação)
├── docker-compose.yml          # (fase de implementação)
├── .github/workflows/          # Pipelines de CI/CD (fase de implementação)
└── docs/
    ├── architecture.md
    ├── adr/
    └── events/
```

## Como buildar (após a implementação)

```bash
./mvnw clean verify
```
