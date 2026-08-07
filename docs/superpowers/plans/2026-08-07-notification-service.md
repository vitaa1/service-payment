# notification-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar o `notification-service` — consumidor puro de `DivergenceDetected` que envia e-mail (Mailhog) e registra a tentativa em `notification_log`, com idempotência por `eventId` e retry/DLQ nativos do Spring AMQP.

**Architecture:** Um listener RabbitMQ (`DivergenceDetectedListener`) delega a um `NotificationService` que faz check-then-send-then-log: consulta `notification_log` por `eventId`, envia o e-mail via `JavaMailSender`, e só então grava o resultado (`SENT`/`FAILED`) numa transação curta. `EmailComposer` monta assunto/corpo a partir do payload (switch exaustivo sobre o `sealed interface DivergenceDetails`). Sem Outbox — o serviço não publica eventos. Espelha a estrutura de pacotes e os padrões de `RabbitConfig`/`QueueNames`/`AbstractIntegrationTest` já usados no `reconciliation-service`.

**Tech Stack:** Java 21, Spring Boot 3.x (`spring-boot-starter-amqp`, `-mail`, `-data-jpa`, `-web` só para Actuator), Flyway, PostgreSQL, JUnit 5 + Mockito, Testcontainers (Postgres + RabbitMQ), `common-events`.

**Spec:** [docs/superpowers/specs/2026-08-07-notification-service-design.md](../specs/2026-08-07-notification-service-design.md)

---

## Nota de topologia importante (aplica-se também ao report-service, futuramente)

`DivergenceDetected` tem **dois consumidores** (`notification`, `report`) que compartilham a mesma routing key de produção (`reconciliation.divergence.detected`) na exchange principal — isso é o fan-out intencional documentado em `docs/events/README.md`. **Isso não pode se repetir na DLX**: se a fila de dead-letter de cada serviço usasse essa mesma routing key para o binding na `payments.events.dlx`, uma mensagem morta de uma fila vazaria para a DLQ do outro serviço (a DLX também é topic e roteia por routing key, não por fila de origem). Este plano usa o **nome da própria fila** como `x-dead-letter-routing-key` e como routing key do binding da DLQ, evitando esse cross-talk. Quando o `report-service` for implementado, sua `RabbitConfig` deve seguir a mesma regra para a fila `report.divergence-detected.q`.

---

### Task 1: Dependências Flyway no pom.xml

O `notification-service/pom.xml` declara `spring-boot-starter-data-jpa` mas **não** declara o Flyway — sem isso, o Spring Boot não roda a migração e o schema nunca é criado. `ddl-auto: none` já está setado em `application.yml`, então sem Flyway o banco fica vazio e todo teste de persistência falha com "relation does not exist".

**Files:**
- Modify: `notification-service/pom.xml`

- [ ] **Step 1: Adicionar as dependências do Flyway**

Em `notification-service/pom.xml`, logo após o bloco do driver `postgresql` (depois de `</dependency>` que fecha `<artifactId>postgresql</artifactId>`, antes do comentário `<!-- Observabilidade (bônus) -->`), adicionar:

```xml
        <!-- Migrations de schema (ADR-0007). Flyway 10+ separa o suporte a Postgres. -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
```

- [ ] **Step 2: Verificar que o módulo builda**

Run: `mvn -pl notification-service -am compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git checkout -b feature/notification-service
git add notification-service/pom.xml
git commit -m "build(notification): adiciona dependencia do Flyway"
```

---

### Task 2: ADR-0011 — Modelo de entrega e idempotência da notificação

**Files:**
- Create: `docs/adr/0011-modelo-entrega-idempotencia-notificacao.md`
- Modify: `docs/adr/README.md`

- [ ] **Step 1: Escrever o ADR**

Criar `docs/adr/0011-modelo-entrega-idempotencia-notificacao.md`:

```markdown
# ADR-0011: Modelo de entrega e idempotência da notificação

- **Status:** Aceito
- **Data:** 2026-08-07

## Contexto

O `notification-service` consome `DivergenceDetected` e envia um e-mail (Mailhog em dev). Diferente do `reconciliation-service`, não há um "caso" a reavaliar — cada `DivergenceDetected` é um fato independente. Ainda assim, é preciso definir: a granularidade da deduplicação, a ordem entre efeito colateral (enviar e-mail) e persistência (gravar o log), a estratégia de retry/DLQ, e o tratamento de mensagens que não conseguem nem ser desserializadas (*poison messages*).

## Decisão

1. **Dedup por `eventId`, sem estado por caso.** Cada `DivergenceDetected` recebido gera no máximo um e-mail. Reavaliações do mesmo `reconciliation_case` chegam como eventos novos (novo `eventId`) e geram novos e-mails — fiel ao contrato documentado; só o `report-service` precisa ordenar por `caseVersion`, o notification não.

2. **Fluxo *check-then-send-then-log*.** `NotificationService` consulta `notification_log` por `eventId`; se já existe com status `SENT`, ignora (reentrega idempotente). Caso contrário, tenta enviar o e-mail. Só grava o resultado **depois** da tentativa de envio: `SENT` em caso de sucesso, `FAILED` em caso de `MailException` (e a exceção é relançada para acionar o retry do listener). Uma tentativa anterior `FAILED` é **atualizada** na mesma linha (`event_id` é `UNIQUE`) quando a reentrega é bem-sucedida.

3. **Retry/DLQ idênticos ao `reconciliation-service`**, sem Resilience4j: retry nativo do Spring AMQP (`initial-interval: 500ms`, `multiplier: 2`, `max-attempts: 5`, `default-requeue-rejected: false`) e uma fila de dead-letter por fila de consumo. **Refinamento de topologia:** como `DivergenceDetected` tem dois consumidores (`notification`, `report`) compartilhando a mesma routing key de produção, a routing key do dead-letter **não pode** ser essa mesma routing key — isso faria a DLX (também topic) vazar mensagens mortas de uma fila para a DLQ do outro serviço. Cada fila usa seu **próprio nome** como routing key de dead-letter.

4. **Poison message não gera log de domínio.** Uma mensagem que falha na desserialização (contrato/JSON inválido) esgota o retry e cai na DLQ sem nunca chegar a `NotificationService.handle` — não há `eventId` de negócio confiável para registrar. Só falhas de **envio** (`MailException`), que ocorrem depois da desserialização bem-sucedida, geram uma linha em `notification_log`.

## Alternativas consideradas

- **Log-then-send** (gravar `PENDING` antes de enviar, atualizar depois) — mais uma escrita síncrona sem mudar o resultado observável (o envio ainda pode falhar depois de gravar); descartada em favor de só persistir o resultado já conhecido.
- **Circuit breaker (Resilience4j) no envio de e-mail** — mantém a consistência do projeto de não usá-lo ainda (é bônus, não adotado em nenhum serviço); fica como evolução se o SMTP externo passar a falhar com frequência.
- **Registrar poison messages em `notification_log` com um status `REJECTED`** — aumentaria o escopo da tabela para um caso que a DLQ já cobre como mecanismo de observação; descartada por ora.

## Consequências

### Positivas
- Nunca perde uma notificação: falha de envio sempre relança e aciona o retry nativo.
- Sem estado agregado por caso — o serviço fica simples, sem lock/reavaliação.
- Mesma política de retry/DLQ do resto do sistema, previsível para operar.

### Negativas / custos
- Duas entregas concorrentes do mesmo `eventId` (cenário raro, mas possível com *at-least-once*) podem resultar em dois e-mails enviados antes de qualquer uma delas gravar `SENT`.
- Sem alerta automático quando a DLQ acumula mensagens (mesma lacuna dos outros serviços — ver `docs/architecture.md` §7).

### Mitigações
- A dedup por `eventId` cobre o caso comum (reentrega depois que já há um `SENT` gravado); só a corrida entre duas entregas *simultâneas* ainda não vistas escapa.
- A DLQ fica observável pelo painel de gerência do RabbitMQ (`localhost:15672`) enquanto não há alerta automatizado.
```

- [ ] **Step 2: Registrar o ADR no índice**

Em `docs/adr/README.md`, adicionar uma linha à tabela do índice, logo após a linha do ADR-0010:

```markdown
| [0011](0011-modelo-entrega-idempotencia-notificacao.md) | Modelo de entrega e idempotência da notificação | Aceito |
```

- [ ] **Step 3: Commit**

```bash
git add docs/adr/0011-modelo-entrega-idempotencia-notificacao.md docs/adr/README.md
git commit -m "docs(adr): registra ADR-0011 (entrega e idempotencia da notificacao)"
```

---

### Task 3: Migração Flyway do schema

**Files:**
- Create: `notification-service/src/main/resources/db/migration/V1__cria_notification_schema.sql`

- [ ] **Step 1: Escrever a migração**

```sql
-- notification_log: uma linha por evento DivergenceDetected processado. Dedup por event_id
-- (ADR-0011): reentrega com status SENT é ignorada; reentrega com status FAILED reataca o
-- envio e atualiza a mesma linha.
CREATE TABLE notification_log (
    id              UUID          PRIMARY KEY,
    event_id        UUID          NOT NULL UNIQUE,
    case_id         UUID          NOT NULL,
    channel         VARCHAR(20)   NOT NULL,
    recipient       VARCHAR(255)  NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    payload_summary JSONB         NOT NULL,
    trace_id        UUID          NOT NULL,
    sent_at         TIMESTAMPTZ   NOT NULL
);
```

- [ ] **Step 2: Commit**

```bash
git add notification-service/src/main/resources/db/migration/V1__cria_notification_schema.sql
git commit -m "feat(notification): migracao Flyway do schema notification_log"
```

---

### Task 4: `NotificationStatus` + `NotificationLog` + `NotificationLogRepository` + IT de persistência

TDD: o teste de integração abaixo só compila depois que as três classes de produção existirem, e só passa depois que a Task 1 (Flyway no pom) e a Task 3 (migração) estiverem em vigor — rode as tasks em ordem.

**Files:**
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/domain/NotificationStatus.java`
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/domain/NotificationLog.java`
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/domain/NotificationLogRepository.java`
- Create: `notification-service/src/test/java/com/portfolio/reconciliation/notification/support/AbstractIntegrationTest.java`
- Test: `notification-service/src/test/java/com/portfolio/reconciliation/notification/domain/NotificationPersistenceIT.java`

- [ ] **Step 1: Escrever a base de testes de integração**

```java
package com.portfolio.reconciliation.notification.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base dos testes de integração do notification-service. Containers singleton (Postgres +
 * RabbitMQ) iniciados uma vez e reusados por todos os ITs do JVM (reapados pelo Ryuk ao fim).
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(
          DockerImageName.parse("rabbitmq:3.13-management-alpine")
              .asCompatibleSubstituteFor("rabbitmq"));

  static {
    POSTGRES.start();
    RABBIT.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    // Retry rápido nos testes — os ITs de poison message não esperam o backoff de produção.
    registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
    registry.add("spring.rabbitmq.listener.simple.retry.max-attempts", () -> "2");
  }
}
```

- [ ] **Step 2: Escrever o teste de persistência (falha por falta das classes de produção)**

```java
package com.portfolio.reconciliation.notification.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portfolio.reconciliation.notification.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Fatia de persistência: migration Flyway sobe o schema e a entidade persiste/recupera. */
class NotificationPersistenceIT extends AbstractIntegrationTest {

  @Autowired NotificationLogRepository repository;

  @Test
  void devePersistirERecuperarPorEventId() {
    UUID eventId = UUID.randomUUID();
    NotificationLog log =
        new NotificationLog(
            UUID.randomUUID(), eventId, UUID.randomUUID(), "EMAIL", "ops@reconciliation.local",
            UUID.randomUUID());
    log.setStatus(NotificationStatus.SENT);
    log.setPayloadSummary("{\"subject\":\"teste\"}");
    log.setSentAt(Instant.now());

    repository.saveAndFlush(log);

    NotificationLog loaded = repository.findByEventId(eventId).orElseThrow();
    assertEquals(NotificationStatus.SENT, loaded.getStatus());
    assertEquals("EMAIL", loaded.getChannel());
    assertNotNull(loaded.getSentAt());
    assertTrue(repository.findByEventId(UUID.randomUUID()).isEmpty());
  }

  @Test
  void deveImporUniqueNoEventId() {
    UUID eventId = UUID.randomUUID();
    NotificationLog first = novoLog(eventId);
    repository.saveAndFlush(first);

    NotificationLog duplicate = novoLog(eventId);
    assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(duplicate));
  }

  private NotificationLog novoLog(UUID eventId) {
    NotificationLog log =
        new NotificationLog(
            UUID.randomUUID(), eventId, UUID.randomUUID(), "EMAIL", "ops@reconciliation.local",
            UUID.randomUUID());
    log.setStatus(NotificationStatus.FAILED);
    log.setPayloadSummary("{}");
    log.setSentAt(Instant.now());
    return log;
  }
}
```

- [ ] **Step 3: Rodar o teste e confirmar que falha (classes de produção não existem)**

Run: `mvn -pl notification-service -am test -Dtest=NotificationPersistenceIT`
Expected: `COMPILATION ERROR` — `NotificationLog`, `NotificationStatus`, `NotificationLogRepository` não existem.

- [ ] **Step 4: Implementar `NotificationStatus`**

```java
package com.portfolio.reconciliation.notification.domain;

/** Resultado de uma tentativa de notificação. Ver ADR-0011. */
public enum NotificationStatus {
  SENT,
  FAILED
}
```

- [ ] **Step 5: Implementar `NotificationLog`**

```java
package com.portfolio.reconciliation.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Uma tentativa de notificação por {@code DivergenceDetected}, uma linha por {@code eventId}
 * (dedup, ADR-0011). {@code caseId} é referência lógica ao {@code reconciliation_case} do
 * reconciliation-service — sem FK cruzando banco (ADR-0003). Ver docs/architecture.md §6.3.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog {

  @Id private UUID id;

  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "case_id")
  private UUID caseId;

  private String channel;

  private String recipient;

  @Enumerated(EnumType.STRING)
  private NotificationStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload_summary")
  private String payloadSummary;

  @Column(name = "trace_id")
  private UUID traceId;

  @Column(name = "sent_at")
  private Instant sentAt;

  protected NotificationLog() {
    // JPA
  }

  public NotificationLog(
      UUID id, UUID eventId, UUID caseId, String channel, String recipient, UUID traceId) {
    this.id = id;
    this.eventId = eventId;
    this.caseId = caseId;
    this.channel = channel;
    this.recipient = recipient;
    this.traceId = traceId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public String getChannel() {
    return channel;
  }

  public String getRecipient() {
    return recipient;
  }

  public NotificationStatus getStatus() {
    return status;
  }

  public void setStatus(NotificationStatus status) {
    this.status = status;
  }

  public String getPayloadSummary() {
    return payloadSummary;
  }

  public void setPayloadSummary(String payloadSummary) {
    this.payloadSummary = payloadSummary;
  }

  public UUID getTraceId() {
    return traceId;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public void setSentAt(Instant sentAt) {
    this.sentAt = sentAt;
  }
}
```

- [ ] **Step 6: Implementar `NotificationLogRepository`**

```java
package com.portfolio.reconciliation.notification.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

  /** Dedup de DivergenceDetected por eventId (ADR-0011). */
  Optional<NotificationLog> findByEventId(UUID eventId);
}
```

- [ ] **Step 7: Rodar o teste e confirmar que passa**

Run: `mvn -pl notification-service -am test -Dtest=NotificationPersistenceIT`
Expected: `BUILD SUCCESS`, 2 testes verdes.

- [ ] **Step 8: Commit**

```bash
git add notification-service/src/main/java/com/portfolio/reconciliation/notification/domain \
        notification-service/src/test/java/com/portfolio/reconciliation/notification
git commit -m "feat(notification): entidade NotificationLog + repositorio + IT de persistencia"
```

---

### Task 5: `NotificationProperties`

**Files:**
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/config/NotificationProperties.java`
- Modify: `notification-service/src/main/java/com/portfolio/reconciliation/notification/NotificationServiceApplication.java`
- Modify: `notification-service/src/main/resources/application.yml`

- [ ] **Step 1: Implementar as properties**

```java
package com.portfolio.reconciliation.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Endereços estáticos de envio (ADR-0011 — sem conceito de contato no domínio). */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(String from, String to) {}
```

- [ ] **Step 2: Habilitar o binding das properties**

Em `NotificationServiceApplication.java`, adicionar `@EnableConfigurationProperties`:

```java
package com.portfolio.reconciliation.notification;

import com.portfolio.reconciliation.notification.config.NotificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(NotificationServiceApplication.class, args);
  }
}
```

- [ ] **Step 3: Configurar os valores default**

Em `notification-service/src/main/resources/application.yml`, adicionar ao final do arquivo (depois do bloco `management:`):

```yaml

# Endereços estáticos de envio (ADR-0011 — sem conceito de contato/cliente no domínio).
notification:
  from: no-reply@reconciliation.local
  to: ops@reconciliation.local
```

- [ ] **Step 4: Verificar que o módulo builda**

Run: `mvn -pl notification-service -am compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add notification-service/src/main/java/com/portfolio/reconciliation/notification/config/NotificationProperties.java \
        notification-service/src/main/java/com/portfolio/reconciliation/notification/NotificationServiceApplication.java \
        notification-service/src/main/resources/application.yml
git commit -m "feat(notification): NotificationProperties (from/to estaticos)"
```

---

### Task 6: `EmailComposer` (TDD)

**Files:**
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/email/EmailComposer.java`
- Test: `notification-service/src/test/java/com/portfolio/reconciliation/notification/email/EmailComposerTest.java`

- [ ] **Step 1: Escrever o teste (falha — `EmailComposer` não existe)**

```java
package com.portfolio.reconciliation.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailComposerTest {

  private final EmailComposer composer = new EmailComposer();

  @Test
  void componeAssuntoECorpoParaDivergent() {
    UUID caseId = UUID.randomUUID();
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            caseId,
            "chg_123",
            DivergenceType.DIVERGENT,
            2,
            "chg_123",
            new DivergentDetails("amount", Map.of(Source.GATEWAY, "199.90", Source.BANK_STATEMENT, "189.90")),
            Instant.parse("2026-08-07T13:00:00Z"));
    EventEnvelope<DivergenceDetectedPayload> envelope =
        new EventEnvelope<>(
            UUID.randomUUID(), "DivergenceDetected", 1, Instant.now(), UUID.randomUUID(),
            "reconciliation-service", payload);

    EmailComposer.EmailContent content = composer.compose(envelope);

    assertThat(content.subject()).isEqualTo("[Conciliação] Divergência DIVERGENT no caso chg_123");
    assertThat(content.body()).contains("chg_123", "amount", "199.90", "189.90", caseId.toString());
  }

  @Test
  void componeCorpoParaMissing() {
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.randomUUID(),
            "chg_456",
            DivergenceType.MISSING,
            1,
            "chg_456",
            new MissingDetails(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), List.of(Source.INTERNAL_ORDER)),
            Instant.parse("2026-08-07T13:00:00Z"));
    EventEnvelope<DivergenceDetectedPayload> envelope =
        new EventEnvelope<>(
            UUID.randomUUID(), "DivergenceDetected", 1, Instant.now(), UUID.randomUUID(),
            "reconciliation-service", payload);

    EmailComposer.EmailContent content = composer.compose(envelope);

    assertThat(content.subject()).isEqualTo("[Conciliação] Divergência MISSING no caso chg_456");
    assertThat(content.body()).contains("INTERNAL_ORDER");
  }

  @Test
  void componeCorpoParaDuplicate() {
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.randomUUID(),
            "chg_789",
            DivergenceType.DUPLICATE,
            1,
            "chg_789",
            new DuplicateDetails(Source.GATEWAY, 2),
            Instant.parse("2026-08-07T13:00:00Z"));
    EventEnvelope<DivergenceDetectedPayload> envelope =
        new EventEnvelope<>(
            UUID.randomUUID(), "DivergenceDetected", 1, Instant.now(), UUID.randomUUID(),
            "reconciliation-service", payload);

    EmailComposer.EmailContent content = composer.compose(envelope);

    assertThat(content.subject()).isEqualTo("[Conciliação] Divergência DUPLICATE no caso chg_789");
    assertThat(content.body()).contains("GATEWAY", "2");
  }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

Run: `mvn -pl notification-service -am test -Dtest=EmailComposerTest`
Expected: `COMPILATION ERROR` — `EmailComposer` não existe.

- [ ] **Step 3: Implementar `EmailComposer`**

```java
package com.portfolio.reconciliation.notification.email;

import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.divergence.DivergenceDetails;
import com.portfolio.reconciliation.events.payload.divergence.DivergentDetails;
import com.portfolio.reconciliation.events.payload.divergence.DuplicateDetails;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import org.springframework.stereotype.Component;

/**
 * Monta o e-mail de alerta a partir de um {@code DivergenceDetected}. O corpo detalha a
 * divergência via um switch exaustivo sobre o sealed interface {@link DivergenceDetails} — o
 * compilador aponta o texto que falta se um novo tipo de divergência for criado.
 */
@Component
public class EmailComposer {

  public record EmailContent(String subject, String body) {}

  public EmailContent compose(EventEnvelope<DivergenceDetectedPayload> envelope) {
    DivergenceDetectedPayload payload = envelope.payload();
    String subject =
        "[Conciliação] Divergência %s no caso %s"
            .formatted(payload.divergenceType(), payload.matchingKey());
    String body =
        """
        Uma divergência foi detectada na conciliação de pagamentos.

        Caso: %s
        Referência externa: %s
        Tipo de divergência: %s
        Detectada em: %s

        %s

        ---
        caseId: %s
        eventId: %s
        traceId: %s
        """
            .formatted(
                payload.matchingKey(),
                payload.externalReference(),
                payload.divergenceType(),
                payload.detectedAt(),
                describe(payload.details()),
                payload.caseId(),
                envelope.eventId(),
                envelope.traceId());
    return new EmailContent(subject, body);
  }

  private String describe(DivergenceDetails details) {
    return switch (details) {
      case DivergentDetails d ->
          "Campo divergente: %s. Valores por fonte: %s".formatted(d.field(), d.values());
      case MissingDetails m ->
          "Fontes esperadas: %s. Fontes ausentes: %s"
              .formatted(m.expectedSources(), m.missingSources());
      case DuplicateDetails du ->
          "Fonte com registro duplicado: %s (%d ocorrências)"
              .formatted(du.source(), du.occurrences());
    };
  }
}
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

Run: `mvn -pl notification-service -am test -Dtest=EmailComposerTest`
Expected: `BUILD SUCCESS`, 3 testes verdes.

- [ ] **Step 5: Commit**

```bash
git add notification-service/src/main/java/com/portfolio/reconciliation/notification/email \
        notification-service/src/test/java/com/portfolio/reconciliation/notification/email
git commit -m "feat(notification): EmailComposer com switch exaustivo sobre DivergenceDetails"
```

---

### Task 7: `NotificationService` (TDD, os 4 caminhos)

**Files:**
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/service/NotificationService.java`
- Test: `notification-service/src/test/java/com/portfolio/reconciliation/notification/service/NotificationServiceTest.java`

- [ ] **Step 1: Escrever o teste (falha — `NotificationService` não existe)**

```java
package com.portfolio.reconciliation.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import com.portfolio.reconciliation.notification.config.NotificationProperties;
import com.portfolio.reconciliation.notification.domain.NotificationLog;
import com.portfolio.reconciliation.notification.domain.NotificationLogRepository;
import com.portfolio.reconciliation.notification.domain.NotificationStatus;
import com.portfolio.reconciliation.notification.email.EmailComposer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;

class NotificationServiceTest {

  private NotificationLogRepository repository;
  private JavaMailSender mailSender;
  private NotificationService service;

  @BeforeEach
  void setUp() {
    repository = mock(NotificationLogRepository.class);
    mailSender = mock(JavaMailSender.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    NotificationProperties properties =
        new NotificationProperties("no-reply@reconciliation.local", "ops@reconciliation.local");
    service =
        new NotificationService(
            repository, mailSender, new EmailComposer(), properties, new ObjectMapper(),
            transactionManager);
  }

  private EventEnvelope<DivergenceDetectedPayload> envelope(UUID eventId) {
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.randomUUID(),
            "chg_1",
            DivergenceType.MISSING,
            2,
            "chg_1",
            new MissingDetails(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), List.of(Source.INTERNAL_ORDER)),
            Instant.parse("2026-08-07T12:00:00Z"));
    return new EventEnvelope<>(
        eventId, "DivergenceDetected", 1, Instant.now(), UUID.randomUUID(),
        "reconciliation-service", payload);
  }

  @Test
  void eventoNovoEnviaEGravaSent() {
    UUID eventId = UUID.randomUUID();
    when(repository.findByEventId(eventId)).thenReturn(Optional.empty());

    service.handle(envelope(eventId));

    verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
  }

  @Test
  void eventoJaEnviadoEhIgnorado() {
    UUID eventId = UUID.randomUUID();
    NotificationLog sent =
        new NotificationLog(
            UUID.randomUUID(), eventId, UUID.randomUUID(), "EMAIL", "ops@reconciliation.local",
            UUID.randomUUID());
    sent.setStatus(NotificationStatus.SENT);
    when(repository.findByEventId(eventId)).thenReturn(Optional.of(sent));

    service.handle(envelope(eventId));

    verify(mailSender, never()).send(any(SimpleMailMessage.class));
    verify(repository, never()).save(any());
  }

  @Test
  void falhaDeEnvioGravaFailedERelanca() {
    UUID eventId = UUID.randomUUID();
    when(repository.findByEventId(eventId)).thenReturn(Optional.empty());
    doThrow(new MailSendException("smtp indisponível")).when(mailSender).send(any(SimpleMailMessage.class));

    assertThrows(MailSendException.class, () -> service.handle(envelope(eventId)));

    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
  }

  @Test
  void reenvioAposFalhaAtualizaParaSent() {
    UUID eventId = UUID.randomUUID();
    NotificationLog failed =
        new NotificationLog(
            UUID.randomUUID(), eventId, UUID.randomUUID(), "EMAIL", "ops@reconciliation.local",
            UUID.randomUUID());
    failed.setStatus(NotificationStatus.FAILED);
    when(repository.findByEventId(eventId)).thenReturn(Optional.of(failed));

    service.handle(envelope(eventId));

    verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(captor.getValue().getId()).isEqualTo(failed.getId());
  }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

Run: `mvn -pl notification-service -am test -Dtest=NotificationServiceTest`
Expected: `COMPILATION ERROR` — `NotificationService` não existe.

- [ ] **Step 3: Implementar `NotificationService`**

```java
package com.portfolio.reconciliation.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.notification.config.NotificationProperties;
import com.portfolio.reconciliation.notification.domain.NotificationLog;
import com.portfolio.reconciliation.notification.domain.NotificationLogRepository;
import com.portfolio.reconciliation.notification.domain.NotificationStatus;
import com.portfolio.reconciliation.notification.email.EmailComposer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orquestra a notificação de um {@code DivergenceDetected}: dedup por {@code eventId},
 * check-then-send-then-log (ADR-0011). {@code MailException} grava {@code FAILED} e relança —
 * quem aciona o retry é o listener (retry nativo do Spring AMQP).
 */
@Service
public class NotificationService {

  private static final String CHANNEL_EMAIL = "EMAIL";

  private final NotificationLogRepository repository;
  private final JavaMailSender mailSender;
  private final EmailComposer emailComposer;
  private final NotificationProperties properties;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;

  public NotificationService(
      NotificationLogRepository repository,
      JavaMailSender mailSender,
      EmailComposer emailComposer,
      NotificationProperties properties,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.mailSender = mailSender;
    this.emailComposer = emailComposer;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void handle(EventEnvelope<DivergenceDetectedPayload> envelope) {
    UUID eventId = envelope.eventId();
    Optional<NotificationLog> existing = repository.findByEventId(eventId);
    if (existing.map(NotificationLog::getStatus).orElse(null) == NotificationStatus.SENT) {
      return; // reentrega já notificada — idempotência por eventId (ADR-0011)
    }

    DivergenceDetectedPayload payload = envelope.payload();
    EmailComposer.EmailContent content = emailComposer.compose(envelope);
    String summary = toJson(payload, content.subject());

    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(properties.from());
    message.setTo(properties.to());
    message.setSubject(content.subject());
    message.setText(content.body());

    try {
      mailSender.send(message);
    } catch (MailException e) {
      persist(eventId, existing, payload.caseId(), envelope.traceId(), NotificationStatus.FAILED, summary);
      throw e;
    }
    persist(eventId, existing, payload.caseId(), envelope.traceId(), NotificationStatus.SENT, summary);
  }

  private void persist(
      UUID eventId,
      Optional<NotificationLog> existing,
      UUID caseId,
      UUID traceId,
      NotificationStatus status,
      String summary) {
    transactionTemplate.executeWithoutResult(
        tx -> {
          NotificationLog log =
              existing.orElseGet(
                  () ->
                      new NotificationLog(
                          UUID.randomUUID(), eventId, caseId, CHANNEL_EMAIL, properties.to(), traceId));
          log.setStatus(status);
          log.setPayloadSummary(summary);
          log.setSentAt(Instant.now());
          repository.save(log);
        });
  }

  private record PayloadSummary(
      UUID caseId, String matchingKey, DivergenceType divergenceType, String externalReference, String subject) {}

  private String toJson(DivergenceDetectedPayload payload, String subject) {
    try {
      return objectMapper.writeValueAsString(
          new PayloadSummary(
              payload.caseId(), payload.matchingKey(), payload.divergenceType(),
              payload.externalReference(), subject));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("falha ao serializar payload_summary", e);
    }
  }
}
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

Run: `mvn -pl notification-service -am test -Dtest=NotificationServiceTest`
Expected: `BUILD SUCCESS`, 4 testes verdes.

- [ ] **Step 5: Commit**

```bash
git add notification-service/src/main/java/com/portfolio/reconciliation/notification/service \
        notification-service/src/test/java/com/portfolio/reconciliation/notification/service
git commit -m "feat(notification): NotificationService com fluxo check-then-send-then-log"
```

---

### Task 8: Topologia RabbitMQ (`QueueNames` + `RabbitConfig`) + `DivergenceDetectedListener`

**Files:**
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/config/QueueNames.java`
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/config/RabbitConfig.java`
- Create: `notification-service/src/main/java/com/portfolio/reconciliation/notification/listener/DivergenceDetectedListener.java`
- Modify: `notification-service/src/main/resources/application.yml`

- [ ] **Step 1: Implementar `QueueNames`**

```java
package com.portfolio.reconciliation.notification.config;

/**
 * Nomes de fila do notification-service. Não pertencem ao common-events — são infra do
 * consumidor (ADR-0005). O nome da fila também é usado como routing key do dead-letter
 * (ADR-0011) — evita cross-talk com a DLQ do report-service, que compartilha a mesma routing
 * key de produção no fan-out de DivergenceDetected. Ver docs/events/README.md.
 */
public final class QueueNames {

  public static final String DIVERGENCE_DETECTED = "notification.divergence-detected.q";
  public static final String DIVERGENCE_DETECTED_DLQ = "notification.divergence-detected.q.dlq";

  private QueueNames() {}
}
```

- [ ] **Step 2: Implementar `RabbitConfig`**

```java
package com.portfolio.reconciliation.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.routing.Exchanges;
import com.portfolio.reconciliation.events.routing.RoutingKeys;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia de consumo do notification-service: declara a fila própria + DLQ + binding
 * (ADR-0005 — nomes de fila não pertencem ao common-events). A routing key do dead-letter é o
 * nome da própria fila, não {@code RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED} (ADR-0011) —
 * essa routing key de produção também alimenta a fila do report-service, e reusá-la na DLX
 * vazaria mensagens mortas entre as duas DLQs.
 */
@Configuration
public class RabbitConfig {

  @Bean
  public TopicExchange paymentsEventsExchange() {
    return ExchangeBuilder.topicExchange(Exchanges.EVENTS).durable(true).build();
  }

  @Bean
  public TopicExchange paymentsEventsDlx() {
    return ExchangeBuilder.topicExchange(Exchanges.EVENTS_DLX).durable(true).build();
  }

  @Bean
  public Queue divergenceDetectedQueue() {
    return QueueBuilder.durable(QueueNames.DIVERGENCE_DETECTED)
        .withArgument("x-dead-letter-exchange", Exchanges.EVENTS_DLX)
        .withArgument("x-dead-letter-routing-key", QueueNames.DIVERGENCE_DETECTED)
        .build();
  }

  @Bean
  public Queue divergenceDetectedDlq() {
    return QueueBuilder.durable(QueueNames.DIVERGENCE_DETECTED_DLQ).build();
  }

  @Bean
  public Binding divergenceDetectedBinding(
      Queue divergenceDetectedQueue, TopicExchange paymentsEventsExchange) {
    return BindingBuilder.bind(divergenceDetectedQueue)
        .to(paymentsEventsExchange)
        .with(RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED);
  }

  @Bean
  public Binding divergenceDetectedDlqBinding(
      Queue divergenceDetectedDlq, TopicExchange paymentsEventsDlx) {
    return BindingBuilder.bind(divergenceDetectedDlq)
        .to(paymentsEventsDlx)
        .with(QueueNames.DIVERGENCE_DETECTED);
  }

  @Bean
  public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }
}
```

- [ ] **Step 3: Implementar `DivergenceDetectedListener`**

```java
package com.portfolio.reconciliation.notification.listener;

import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.notification.config.QueueNames;
import com.portfolio.reconciliation.notification.service.NotificationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome {@code DivergenceDetected} e delega ao {@link NotificationService}. Exceção não
 * tratada aciona o retry nativo do Spring AMQP; ao esgotar, cai na DLQ (ADR-0011).
 */
@Component
public class DivergenceDetectedListener {

  private final NotificationService notificationService;

  public DivergenceDetectedListener(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @RabbitListener(queues = QueueNames.DIVERGENCE_DETECTED)
  public void onMessage(EventEnvelope<DivergenceDetectedPayload> envelope) {
    notificationService.handle(envelope);
  }
}
```

- [ ] **Step 4: Configurar retry nativo no `application.yml`**

Em `notification-service/src/main/resources/application.yml`, dentro do bloco `spring.rabbitmq`, adicionar `listener` (logo abaixo de `password`):

```yaml
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        # Retry nativo com backoff exponencial; ao esgotar, rejeita (não recoloca na fila) —
        # o broker então dead-letra para a DLQ via o argumento da fila (ADR-0011).
        retry:
          enabled: true
          initial-interval: 500ms
          multiplier: 2
          max-attempts: 5
        default-requeue-rejected: false
```

- [ ] **Step 5: Verificar que o módulo builda**

Run: `mvn -pl notification-service -am compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add notification-service/src/main/java/com/portfolio/reconciliation/notification/config \
        notification-service/src/main/java/com/portfolio/reconciliation/notification/listener \
        notification-service/src/main/resources/application.yml
git commit -m "feat(notification): topologia RabbitMQ + listener de DivergenceDetected"
```

---

### Task 9: `NotificationEndToEndIT`

Cobre, de ponta a ponta (RabbitMQ + Postgres reais, `JavaMailSender` mockado): dedup por `eventId`, falha de envio esgotando o retry e caindo na DLQ, e mensagem poison caindo na DLQ sem gerar log.

**Files:**
- Test: `notification-service/src/test/java/com/portfolio/reconciliation/notification/listener/NotificationEndToEndIT.java`

- [ ] **Step 1: Escrever o teste**

```java
package com.portfolio.reconciliation.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.DivergenceType;
import com.portfolio.reconciliation.events.EventEnvelope;
import com.portfolio.reconciliation.events.Source;
import com.portfolio.reconciliation.events.payload.DivergenceDetectedPayload;
import com.portfolio.reconciliation.events.payload.divergence.MissingDetails;
import com.portfolio.reconciliation.events.routing.EventTypes;
import com.portfolio.reconciliation.events.routing.Exchanges;
import com.portfolio.reconciliation.events.routing.RoutingKeys;
import com.portfolio.reconciliation.notification.config.QueueNames;
import com.portfolio.reconciliation.notification.domain.NotificationLog;
import com.portfolio.reconciliation.notification.domain.NotificationLogRepository;
import com.portfolio.reconciliation.notification.domain.NotificationStatus;
import com.portfolio.reconciliation.notification.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Fatia de ponta a ponta: publica DivergenceDetected na exchange como o reconciliation faria, o
 * listener consome e o NotificationService reage. Cobre dedup, falha de envio esgotando o
 * retry (DLQ) e mensagem poison (DLQ).
 */
class NotificationEndToEndIT extends AbstractIntegrationTest {

  @Autowired RabbitTemplate rabbitTemplate;
  @Autowired ObjectMapper objectMapper;
  @Autowired NotificationLogRepository repository;
  @MockBean JavaMailSender mailSender;

  @BeforeEach
  void limpa() {
    repository.deleteAll();
    reset(mailSender);
    drain(QueueNames.DIVERGENCE_DETECTED_DLQ);
  }

  private void drain(String queue) {
    while (rabbitTemplate.receive(queue) != null) {
      // esvazia
    }
  }

  @Test
  void publicaNaExchangeEhConsumidoEGravaSent() throws Exception {
    UUID eventId = UUID.randomUUID();
    publish(envelope(eventId, "chg_" + eventId));

    NotificationLog log = awaitLog(eventId);
    assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
  }

  @Test
  void reentregaDoMesmoEventIdJaEnviadoNaoReenviaEmail() throws Exception {
    UUID eventId = UUID.randomUUID();
    EventEnvelope<DivergenceDetectedPayload> envelope = envelope(eventId, "chg_" + eventId);
    publish(envelope);
    awaitLog(eventId);
    reset(mailSender); // limpa a contagem da 1a entrega

    publish(envelope); // reentrega manual do mesmo eventId

    Thread.sleep(1000); // dá tempo do listener processar, se fosse processar
    org.mockito.Mockito.verifyNoInteractions(mailSender);
  }

  @Test
  void falhaDeEnvioEsgotaRetryECaiNaDlq() throws Exception {
    doThrow(new MailSendException("smtp indisponível")).when(mailSender).send(any(SimpleMailMessage.class));
    UUID eventId = UUID.randomUUID();
    publish(envelope(eventId, "chg_" + eventId));

    Message dead = rabbitTemplate.receive(QueueNames.DIVERGENCE_DETECTED_DLQ, 15000);
    assertNotNull(dead, "mensagem deve cair na DLQ após esgotar o retry de envio");

    NotificationLog log = repository.findByEventId(eventId).orElseThrow();
    assertThat(log.getStatus()).isEqualTo(NotificationStatus.FAILED);
  }

  @Test
  void mensagemInvalidaEsgotaRetryECaiNaDlqSemLog() {
    Message poison =
        MessageBuilder.withBody("{ isto nao eh json valido".getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    rabbitTemplate.send(Exchanges.EVENTS, RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED, poison);

    Message dead = rabbitTemplate.receive(QueueNames.DIVERGENCE_DETECTED_DLQ, 10000);
    assertNotNull(dead, "mensagem inválida deve cair na DLQ após esgotar o retry");
    assertThat(repository.count()).isZero();
  }

  private void publish(EventEnvelope<DivergenceDetectedPayload> envelope) throws Exception {
    String json = objectMapper.writeValueAsString(envelope);
    Message message =
        MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();
    rabbitTemplate.send(Exchanges.EVENTS, RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED, message);
  }

  private EventEnvelope<DivergenceDetectedPayload> envelope(UUID eventId, String matchingKey) {
    DivergenceDetectedPayload payload =
        new DivergenceDetectedPayload(
            UUID.randomUUID(),
            matchingKey,
            DivergenceType.MISSING,
            1,
            matchingKey,
            new MissingDetails(List.of(Source.GATEWAY, Source.INTERNAL_ORDER), List.of(Source.INTERNAL_ORDER)),
            Instant.now());
    return new EventEnvelope<>(
        eventId, EventTypes.DIVERGENCE_DETECTED, 1, Instant.now(), UUID.randomUUID(),
        "reconciliation-service", payload);
  }

  /** Polling simples — o consumo é assíncrono, sem framework extra de espera no projeto. */
  private NotificationLog awaitLog(UUID eventId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      Optional<NotificationLog> found = repository.findByEventId(eventId);
      if (found.isPresent()) {
        return found.get();
      }
      Thread.sleep(200);
    }
    fail("notification_log para eventId " + eventId + " não apareceu a tempo");
    return null;
  }
}
```

- [ ] **Step 2: Rodar o teste**

Run: `mvn -pl notification-service -am verify -Dtest=NotificationEndToEndIT`
Expected: `BUILD SUCCESS`, 4 testes verdes. (Sobe Testcontainers Postgres + RabbitMQ — Docker precisa estar rodando.)

- [ ] **Step 3: Commit**

```bash
git add notification-service/src/test/java/com/portfolio/reconciliation/notification/listener
git commit -m "test(notification): IT de ponta a ponta (dedup, falha de envio, poison)"
```

---

### Task 10: Documentar escopo futuro no README raiz

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Adicionar subseção ao final de "Possíveis evoluções futuras"**

Em `README.md`, depois da seção `### Patterns avaliados e descartados (com motivo)` (ao final do arquivo), adicionar:

```markdown

### notification-service — evoluções não implementadas nesta fatia

- **Roteamento de destinatário por `divergenceType`/severidade.** Hoje o `to` é único e estático (`NotificationProperties`); rotear por tipo de problema é aditivo e não muda o modelo de dedup (ADR-0011).
- **E-mail HTML/template.** O corpo hoje é texto puro (`SimpleMailMessage`); um template HTML trocaria só o `EmailComposer`, sem tocar no fluxo de envio/persistência.
- **Canais além de e-mail (SMS, Slack, etc.).** Exigiria extrair uma porta (`MailNotifier`/`NotificationChannel`) entre `NotificationService` e o envio concreto — não feito agora por YAGNI (só há um canal).
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: registra evolucoes futuras do notification-service"
```

---

### Task 11: Verificação final, gate de review e PR

- [ ] **Step 1: Build completo do módulo**

Run: `mvn -pl notification-service -am verify`
Expected: `BUILD SUCCESS`, todos os testes (unitários + IT) verdes.

- [ ] **Step 2: Build completo do repositório (garante que nada quebrou nos outros módulos)**

Run: `mvn clean verify`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Rodar os subagents de review**

Invocar o agent `code-reviewer` e o agent `security-guard` (via Task tool) sobre o diff da branch `feature/notification-service` contra `main`. Corrigir achados relevantes antes de abrir o PR — cada correção é um commit adicional na mesma branch.

- [ ] **Step 4: Push e abertura do PR**

```bash
git push -u origin feature/notification-service
```

```bash
gh pr create --title "feat(notification): fatiamento inicial do notification-service" --body "$(cat <<'EOF'
## Summary
- Implementa o notification-service: consumidor puro de DivergenceDetected, envia e-mail (Mailhog) e registra em notification_log.
- ADR-0011 registra o modelo de entrega/idempotência (dedup por eventId, check-then-send-then-log, retry/DLQ, poison message).
- Corrige a topologia da DLQ para usar o nome da fila como routing key de dead-letter (evita cross-talk com a futura DLQ do report-service, que compartilha a routing key de produção de DivergenceDetected).

## Test plan
- [ ] `mvn -pl notification-service -am verify` local, verde
- [ ] `mvn clean verify` na raiz, verde
- [ ] CI (`build`) verde no PR
EOF
)"
```

Expected: PR criado, CI (`mvn -B verify`) disparado e verde antes do merge (ruleset da `main` exige o check).

---

## Cobertura da spec (auto-verificação)

| Decisão da spec | Task |
|---|---|
| 1. Dedup por eventId, sem estado por caso | Task 7 (`handle`) |
| 2. check-then-send-then-log | Task 7 |
| 3. Retry nativo idêntico ao reconciliation | Task 8 (Step 4, `application.yml`) |
| 4. Poison sem log de domínio | Task 8 (`RabbitConfig`) + Task 9 (teste) |
| 5. Destinatário estático (`NotificationProperties`) | Task 5 |
| 6. E-mail texto puro + switch exaustivo | Task 6 |
| 7. Persistência (`payload_summary`, `channel`, `status`, JSONB, `sent_at`) | Task 3, Task 4, Task 7 |
| 8. ADR-0011 | Task 2 |
| 9. Estrutura de pacotes | Tasks 4–8 (paths) |
| 10. Testes (4 tipos) | Tasks 4, 6, 7, 9 |
| 11. 5 commits + gate + PR | Estrutura geral do plano (11 commits granulares) + Task 11 |
| Fora de escopo documentado | Task 10 |
