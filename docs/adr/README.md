# Architecture Decision Records (ADRs)

Este diretório registra as **decisões de arquitetura** do projeto — o *porquê* por trás da estrutura, não só o *o quê*. Cada ADR captura o contexto em que a decisão foi tomada, a decisão em si, as alternativas consideradas e as consequências (boas e ruins).

Formato inspirado no modelo de Michael Nygard. Use [`template.md`](template.md) para novos registros.

## Índice

| # | Título | Status |
|---|---|---|
| [0001](0001-adocao-de-microsservicos.md) | Adoção de arquitetura de microsserviços | Aceito |
| [0002](0002-comunicacao-hibrida-mensageria-rest.md) | Comunicação híbrida: mensageria entre serviços, REST na borda | Aceito |
| [0003](0003-database-per-service.md) | Database per Service e escopo do módulo common-events | Aceito |
| [0004](0004-cqrs-no-report-service.md) | CQRS parcimonioso no report-service | Aceito |
| [0005](0005-design-serializacao-common-events.md) | Design de serialização e tipagem do common-events | Aceito |
| [0006](0006-transactional-outbox.md) | Publicação de eventos via Transactional Outbox | Aceito |
| [0007](0007-flyway-para-migrations.md) | Flyway para migrations de banco | Aceito |
| [0008](0008-idempotencia-da-ingestao.md) | Idempotência da ingestão via Idempotency-Key | Aceito |

## Status possíveis

- **Proposto** — em discussão
- **Aceito** — decisão em vigor
- **Substituído por ADR-XXXX** — decisão superada por outra
- **Depreciado** — não se aplica mais
