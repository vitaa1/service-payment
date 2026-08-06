# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Documentação e comunicação com o usuário em **pt-BR**; identificadores de código em inglês.

## Estado do projeto

Projeto de portfólio (Payment Reconciliation Engine). O **esqueleto e a infraestrutura estão prontos**; a lógica de negócio, não. Cada módulo contém hoje apenas uma classe `@SpringBootApplication` (ou, no caso do `common-events`, árvores `src` vazias com `.gitkeep`). **Não existem testes nem classes de domínio ainda** — no momento, `docs/` é mais autoritativo que o código.

Ao implementar qualquer coisa, `docs/architecture.md`, `docs/adr/` e `docs/events/` são a especificação. Em particular, **`docs/events/` é a fonte da verdade declarada do `common-events`** — os records Java devem espelhar exatamente esses documentos. O vocabulário canônico do domínio está em **`CONTEXT.md`** (glossário). As decisões de serialização/tipagem do `common-events` estão no **ADR-0005** e o padrão de publicação (Outbox) no **ADR-0006**.

## Convenção de idioma

Documentação, comentários dos POMs e comentários inline em **pt-BR**; identificadores de código, nomes de pacote, nomes de evento e nomes de coluna em **inglês**. Siga esse padrão ao criar arquivos novos.

## Comandos

Build Maven multi-módulo, JDK 21. **Não há Maven wrapper (`mvnw`) e o `mvn` não está no PATH deste ambiente** — o Java 21 está disponível, o Maven não. Os builds precisam passar pelo Maven embutido do IntelliJ, por uma instalação local do Maven, ou por Docker. Não presuma que um `mvn` puro vai funcionar; verifique antes.

```bash
mvn clean verify                      # build completo + testes (o que o CI roda: mvn -B verify)
mvn -pl ingestion-service -am verify  # builda um serviço e suas dependências (-am é obrigatório: common-events é módulo irmão)
mvn -pl ingestion-service test -Dtest=MatchingKeyTest           # uma única classe de teste
mvn -pl ingestion-service test -Dtest=MatchingKeyTest#deveGerar # um único método de teste
```

Infraestrutura local:

```bash
docker compose up --build             # 4 serviços + 4 Postgres + RabbitMQ + Mailhog
docker compose up -d ingestion-db reconciliation-db notification-db report-db rabbitmq mailhog
                                      # só a infra — daí rode os serviços pela IDE contra os defaults de localhost
```

Cada `application.yml` tem default `localhost` e a porta do Postgres **mapeada no host**, então serviços rodados pela IDE funcionam contra a infra do compose sem configuração extra.

| Serviço | HTTP | Porta do banco (host) |
|---|---|---|
| ingestion-service | 8081 | 5433 |
| reconciliation-service | 8082 | 5434 |
| notification-service | 8083 | 5435 |
| report-service | 8084 | 5436 |
| RabbitMQ | 15672 (guest/guest), AMQP 5672 | — |
| Mailhog | 8025 (UI), SMTP 1025 | — |

Os Dockerfiles buildam com a **raiz do repositório** como contexto (`docker build -f ingestion-service/Dockerfile .`), porque precisam do POM pai e do `common-events`. Adicionar um módulo novo exige acrescentar a linha `COPY <módulo>/pom.xml` em **todos** os Dockerfiles de serviço, senão a camada de cache de dependências quebra.

## Arquitetura

Quatro microsserviços Spring Boot mais uma biblioteca de contratos compartilhada, comunicando-se por um único topic exchange do RabbitMQ.

```
Cliente --REST--> ingestion-service --TransactionNormalized--> reconciliation-service
                                                                     |
                                         ReconciliationCompleted ----+---- DivergenceDetected
                                                     |                         |        |
                                               report-service <----------------+   notification-service
Cliente --REST--> report-service (lê a própria projeção)                              (e-mail -> Mailhog)
```

- **ingestion-service** — valida e normaliza payloads brutos de três fontes (`GATEWAY`, `BANK_STATEMENT`, `INTERNAL_ORDER`), persiste o corpo cru para auditoria, publica `TransactionNormalized` e devolve `202 Accepted`. Nenhuma lógica de conciliação ("ingestão burra, núcleo inteligente").
- **reconciliation-service** — o núcleo. Agrupa registros por `matchingKey` (`externalReference | amount | transactionDate`) em um `reconciliation_case`, avalia o caso como `MATCHED | DIVERGENT | MISSING | DUPLICATE`, emite `ReconciliationCompleted` sempre e `DivergenceDetected` só quando há problema. Os casos são **reavaliados** conforme as pernas seguintes chegam, então um caso pode ir de `MISSING` para `MATCHED`.
- **notification-service** — consome `DivergenceDetected`, envia e-mail e registra em `notification_log`.
- **report-service** — lado de leitura do CQRS. Mantém uma projeção desnormalizada alimentada exclusivamente por eventos; atende as consultas REST a partir dela.
- **common-events** — envelope de evento + records de payload + constantes de roteamento. Nada além disso.

### Regras inegociáveis (vindas dos ADRs)

Violar qualquer uma delas contraria um ADR aceito — levante a questão antes de fazê-lo.

1. **Nenhum REST de negócio entre serviços de processamento** (ADR-0002). REST só cruza a borda (cliente → ingestion, cliente → report). Endpoints do Actuator são exceção — são operacionais, não API de negócio.
2. **Database per service** (ADR-0003). Quatro bancos Postgres separados, sem FKs nem joins cruzando bancos. Referências entre serviços são apenas identificadores lógicos (ex.: `case_id` em `notification_log`).
3. **`common-events` tem escopo estrito** (ADR-0003). Apenas records de mensagem e constantes de roteamento. **Proibido:** entidades JPA, lógica de negócio, DTOs de REST, qualquer dependência de Spring Data/Web. A única dependência é `jackson-annotations`.
4. **Todo consumidor precisa deduplicar por `eventId`** (ADR-0002). O RabbitMQ é at-least-once; a mesma mensagem vai chegar duas vezes. Garantido por colunas `event_id UNIQUE` / tabela `processed_event`.
5. **Nunca assuma ordem das mensagens.** Use `payload.caseVersion` contra um `last_version` persistido para descartar atualizações antigas.
6. **`amount` trafega como string decimal** — converta para `BigDecimal`, nunca `double`.
7. **O `traceId` nasce na ingestão e é propagado** no envelope de todo evento derivado.
8. **Schemas são gerenciados explicitamente** — `ddl-auto: none` em todos os serviços. Tabelas novas exigem migração, não auto-DDL do Hibernate.
9. **Produtores publicam via Outbox, nunca direto** (ADR-0006). Todo evento é gravado na tabela `outbox` na mesma transação do estado de negócio; um relay (`@Scheduled` + `FOR UPDATE SKIP LOCKED`) publica e marca `published_at` só após o confirm do broker. Nunca faça `convertAndSend` direto após um `save` — isso reintroduz o dual-write.

### Topologia de mensageria

Exchange `payments.events` (topic, durável), DLX `payments.events.dlx`. Routing keys: `transaction.normalized`, `reconciliation.completed`, `reconciliation.divergence.detected`. Quatro filas, cada uma com sua `.dlq` — a tabela de bindings está em `docs/events/README.md`. `DivergenceDetected` faz fan-out para duas filas independentes (report + notification), que é a demonstração concreta do desacoplamento por evento; não junte as duas em uma só.

### Envelope

Todo evento usa o mesmo envelope: `eventId`, `eventType`, `eventVersion`, `occurredAt` (ISO-8601 UTC), `traceId`, `producer`, `payload`. Note que `producer` (o *serviço* emissor) é deliberadamente distinto de `payload.source` (a origem *do dado*). Mudanças aditivas de contrato mantêm o `eventVersion`; mudanças incompatíveis incrementam a versão ou criam um novo `eventType`. A desserialização deve ignorar campos desconhecidos.

## Convenções

- Raiz de pacote `com.portfolio.reconciliation.<serviço>` (ex.: `...reconciliation.ingestion`, `...reconciliation.reconciliation`).
- O código existente está formatado com **google-java-format** (indentação de 2 espaços). O Spotless foi adicionado e depois removido do build de propósito (`ec0e328`), então a formatação não é imposta automaticamente — siga o estilo existente manualmente.
- Gestão de versões e dependências vive no POM pai (`dependencyManagement` + BOMs do Testcontainers e do Resilience4j). Os módulos filhos declaram as starters **sem versão**.
- Testes: JUnit 5 + Mockito para unitários; Testcontainers (Postgres + RabbitMQ) para integração. As dependências do Testcontainers já estão declaradas em todos os POMs de serviço.
- Decisões de arquitetura novas rendem um ADR em `docs/adr/` usando o `template.md`, mais uma entrada na tabela de índice dos ADRs.

## Fluxo de trabalho

Trabalho de implementação acontece em feature branch com PR; o CI (`.github/workflows/ci.yml`, `mvn -B verify` no JDK 21) roda em pushes e PRs para a `main`. Não commite direto na `main` (o ruleset do repositório exige o check `build` verde antes do merge).

Há subagents de review em `.claude/agents/` — `code-reviewer` (bugs, arquitetura, qualidade) e `security-guard` (segredos, injeção, dados financeiros, mensageria). Use-os antes de abrir o PR.
