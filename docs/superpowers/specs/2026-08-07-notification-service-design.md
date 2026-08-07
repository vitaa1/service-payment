# Design — notification-service (fatiamento inicial)

- **Data:** 2026-08-07
- **Status:** Aprovado (entendimento consolidado via grilling com o usuário)

## Contexto

O `notification-service` hoje só tem o esqueleto (`@SpringBootApplication` + `application.yml`). É o único serviço puramente reativo do sistema: consome `DivergenceDetected`, envia um e-mail (Mailhog em dev) e registra a tentativa em `notification_log`. Não publica eventos, não expõe REST de negócio, não tem `@Scheduled`. Especificação de referência: `docs/architecture.md` §6.3, `docs/events/divergence-detected.md`, `docs/events/README.md` (bindings/DLQ).

## Papel do serviço

Consumidor puro da fila `notification.divergence-detected.q`. Sem Outbox (não produz eventos — ADR-0006 não se aplica aqui). Banco próprio `notification_db`.

## Decisões

1. **Granularidade da dedup.** Dedup por `eventId` (`notification_log.event_id UNIQUE`) — um e-mail por `DivergenceDetected` recebido, sem estado agregado por caso. Reavaliações do mesmo `reconciliation_case` que gerem novos `DivergenceDetected` (com novo `eventId`) geram novos e-mails independentes; isso é fiel ao contrato documentado — só o `report-service` ordena por `caseVersion`, o notification não precisa.

2. **Fluxo check-then-send-then-log.**
   - `findByEventId(eventId)`: se já existe um registro com status `SENT`, ignora (reentrega idempotente).
   - Se não existe registro, ou existe com status `FAILED`, tenta enviar o e-mail.
   - Envio ok → grava/atualiza a linha (`event_id` é UNIQUE — uma tentativa anterior `FAILED` é **atualizada** para `SENT`, não duplicada) e retorna normalmente.
   - `MailException` → grava/atualiza a linha como `FAILED` (transação própria, curta) → relança a exceção para acionar o retry nativo do listener.
   - Prioridade: nunca perder a notificação. Uma corrida entre duas entregas concorrentes do mesmo `eventId` pode gerar duplicata de envio (e-mail mandado duas vezes) — aceitável; perda de notificação não é.

3. **Retry/DLQ.** Retry nativo do Spring AMQP, mesmos parâmetros do `reconciliation-service` (`initial-interval: 500ms`, `multiplier: 2`, `max-attempts: 5`, `default-requeue-rejected: false`). Topologia de fila (`notification.divergence-detected.q` + `.dlq` + binding) espelha o padrão do `RabbitConfig`/`QueueNames` do reconciliation. Sem Resilience4j (mantém consistência com o restante do projeto, que também não usa).

4. **Poison message.** Falha de desserialização/contrato inválido sobe sem tratamento especial, cicla os 5 attempts e cai na DLQ — não gera linha em `notification_log` (só `MailException` gera log; erro de contrato não é uma "tentativa de notificação" que falhou, é uma mensagem inválida). O desperdício dos 5 ciclos é aceito.

5. **Destinatário.** Endereços estáticos via `NotificationProperties` (`spring.mail` já configurado): `from=no-reply@reconciliation.local`, `to=ops@reconciliation.local`. Não há conceito de contato/cliente no domínio — o glossário (`CONTEXT.md`) não define destinatário de notificação, então o e-mail simula a equipe de back-office/operações que trata divergências.

6. **Conteúdo do e-mail.** Texto puro (`SimpleMailMessage`, sem HTML/template). Corpo em pt-BR. Assunto: `[Conciliação] Divergência <TIPO> no caso <matchingKey>`. Um `EmailComposer` monta o corpo com `switch` exaustivo sobre o `sealed interface DivergenceDetails` (`DivergentDetails`, `MissingDetails`, `DuplicateDetails`), incluindo um rodapé de rastreabilidade (`caseId`, `traceId`, `eventId`).

7. **Persistência.** `payload_summary` (JSONB) guarda os campos-chave do evento recebido + o `subject` do e-mail (não o corpo). `channel = "EMAIL"` (string, extensível por design conforme `docs/architecture.md`). `status` como enum Java mapeado como `String` (`SENT` | `FAILED`). JSONB via `@JdbcTypeCode(SqlTypes.JSON)` — mesmo padrão de `ReconciliationCase.divergenceDetails`. `sent_at` gravado explicitamente no código (`Instant.now()`), não pelo banco.

8. **ADR novo.** `docs/adr/0011-modelo-entrega-idempotencia-notificacao.md` registrando as decisões 1–4 (granularidade da dedup, check-then-send-then-log, retry/DLQ, poison message) — são decisões de arquitetura não cobertas por ADR existente.

9. **Estrutura de pacotes** (espelha `reconciliation-service`, adaptado ao papel de consumidor puro):
   ```
   com.portfolio.reconciliation.notification
   ├── config      (RabbitConfig, QueueNames, NotificationProperties)
   ├── domain      (NotificationLog entity + repository, NotificationStatus enum)
   ├── email       (EmailComposer)
   ├── listener    (DivergenceDetectedListener)
   └── service     (NotificationService)
   ```
   `JavaMailSender` é injetado diretamente no `NotificationService` (sem porta/abstração própria — YAGNI; criar uma porta `MailNotifier` fica registrado como evolução futura se um segundo canal aparecer).

10. **Testes (TDD).**
    - `EmailComposerTest` — assunto/corpo para os três tipos de `DivergenceDetails` (unitário puro).
    - `NotificationServiceTest` — os 4 caminhos (novo evento → SENT; replay de evento já SENT → ignora; falha de envio → FAILED + relança; replay após FAILED → tenta de novo) via Mockito, com `@MockBean`/mock de `JavaMailSender` e repositório.
    - `NotificationPersistenceIT` — Testcontainers Postgres, mapeamento JPA/JSONB real.
    - `NotificationEndToEndIT` — Testcontainers Postgres + RabbitMQ, `@MockBean JavaMailSender`: publica `DivergenceDetected` real na exchange e verifica (a) dedup por `eventId`, (b) `MailException` simulada → mensagem cai na DLQ após esgotar retries, (c) mensagem poison (payload inválido) → DLQ sem linha em `notification_log`.

11. **Fluxo de entrega.** 5 commits (ADR-0011 → schema/persistência `NotificationLog` → `EmailComposer` → `NotificationService` → `listener`/topologia RabbitMQ), seguidos do portão `code-reviewer` + `security-guard` antes de abrir o PR contra `main` (CI `mvn -B verify`). Branch `feature/notification-service`.

## Schema (`V1__cria_notification_schema.sql`)

Fixo pelo `docs/architecture.md` §6.3 — sem alterações:

```sql
notification_log(
  id UUID PK,
  event_id UUID UNIQUE,     -- dedup de DivergenceDetected
  case_id UUID,              -- referência lógica, sem FK cruzando banco
  channel VARCHAR,           -- "EMAIL"
  recipient VARCHAR,
  status VARCHAR,            -- SENT | FAILED
  payload_summary JSONB,
  trace_id UUID,
  sent_at TIMESTAMPTZ
)
```

## Fora de escopo (evoluções futuras — registrar no README do serviço)

- Roteamento de destinatário por `divergenceType`/severidade.
- E-mail HTML/template.
- Canais além de e-mail (exigiria extrair uma porta `MailNotifier`).

## Verificação cruzada com o código existente

- `DivergenceDetails` é `sealed interface` com 3 `permits` (`DivergentDetails`, `MissingDetails`, `DuplicateDetails`) — confirma a viabilidade do `switch` exaustivo no `EmailComposer`.
- `@JdbcTypeCode(SqlTypes.JSON)` já é o padrão usado em `ReconciliationCase.divergenceDetails` — mesma abordagem para `payload_summary`.
- `RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED` e `EventTypes.DIVERGENCE_DETECTED` já existem em `common-events` — reusar, não recriar.
- `notification-service/pom.xml` já declara `spring-boot-starter-mail`, `spring-boot-starter-amqp`, `spring-boot-starter-data-jpa`, Testcontainers (Postgres + RabbitMQ) — nenhuma dependência nova necessária.
