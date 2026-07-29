# ADR-0001: Adoção de arquitetura de microsserviços

- **Status:** Aceito
- **Data:** 2026-07-29

## Contexto

O motor de conciliação tem responsabilidades naturalmente distintas — ingerir dados, conciliar, notificar e reportar — com perfis de carga e de mudança diferentes:

- A **ingestão** é orientada a picos (webhooks, importações em lote) e muda quando surge uma nova fonte.
- A **conciliação** é intensiva em CPU e concentra a regra de negócio, que evolui com frequência.
- A **notificação** é I/O-bound (envio de e-mail) e pode falhar de forma independente.
- O **relatório** é read-heavy e tem requisitos de latência de consulta próprios.

Além do domínio, este é um **projeto de portfólio** cujo objetivo explícito é demonstrar competência com mensageria, Docker, CI/CD e testes de integração — habilidades que só aparecem de forma convincente em um sistema distribuído.

## Decisão

Adotamos uma **arquitetura de microsserviços** com quatro serviços independentes (`ingestion`, `reconciliation`, `notification`, `report`), cada um implantável e escalável separadamente, comunicando-se por eventos.

## Alternativas consideradas

- **Monólito modular (recomendado para muitos cenários reais).** Um único deploy com módulos bem separados seria mais simples de operar e testar, e para um produto em estágio inicial provavelmente seria a escolha *tecnicamente* mais sensata. Foi descartado aqui porque não exercita mensageria real entre processos, orquestração de múltiplos containers nem testes de integração distribuídos — que são exatamente as competências que o projeto precisa demonstrar.
- **Serverless / functions.** Bom para a ingestão orientada a eventos, mas adicionaria dependência de um provedor específico e dificultaria rodar o sistema inteiro localmente com docker-compose.

## Consequências

### Positivas
- Cada serviço tem uma responsabilidade única e clara, testável isoladamente.
- Falha de um serviço (ex.: notificação) não derruba os demais.
- Demonstra explicitamente as competências-alvo (mensageria, Docker, CI/CD, testes de integração).
- Permite escalar o núcleo de conciliação independentemente da borda.

### Negativas / custos
- Complexidade operacional maior: quatro processos, quatro bancos, um broker.
- Consistência passa a ser **eventual** entre serviços.
- Depuração de um fluxo cruza fronteiras de processo.
- Overhead de infraestrutura para rodar tudo localmente.

### Mitigações
- **Rastreabilidade** via `traceId` propagado no envelope de todos os eventos (ver [ADR-0002](0002-comunicacao-hibrida-mensageria-rest.md) e [docs/events](../events/README.md)).
- **docker-compose** sobe o sistema inteiro com um comando.
- Escopo deliberadamente enxuto: quatro serviços, sem service mesh nem API gateway — o suficiente para demonstrar, sem over-engineering.
