# ADR-0007: Flyway para migrations de banco

- **Status:** Aceito
- **Data:** 2026-08-06

## Contexto

Todos os serviços usam `ddl-auto: none` (regra inegociável, ADR-0003): o schema é gerenciado explicitamente, não gerado pelo Hibernate. Isso exige uma ferramenta de migração versionada. A escolha é um **padrão de projeto** — cada um dos quatro serviços terá suas próprias migrations, e trocar de ferramenta depois é caro. Restrições: bancos são **todos PostgreSQL** (database-per-service), migrations são **forward-only** (não há necessidade de rollback automatizado num portfólio), e o valor de demonstração inclui SQL legível.

## Decisão

Adotamos o **Flyway**. As migrations são arquivos SQL versionados (`V1__descricao.sql`) no classpath de cada serviço (`src/main/resources/db/migration`), aplicadas na subida via a autoconfiguração do Spring Boot.

## Alternativas consideradas

- **Liquibase.** Changesets em XML/YAML/JSON/SQL, com abstração *database-agnostic* e rollback embutido. Descartado: a abstração database-agnostic — seu principal diferencial — é desperdiçada num cenário Postgres-only, e o rollback automatizado é overkill para migrations forward-only. O custo é mais verbosidade e uma camada de indireção sobre o SQL.
- **`ddl-auto: update`/`create` do Hibernate.** Proibido pelo ADR-0003 — auto-DDL não é rastreável nem revisável, e diverge entre ambientes.

## Consequências

### Positivas
- Migrations **são SQL puro** — legíveis, revisáveis em PR, e honestas sobre o que roda no banco.
- Autoconfiguração first-class no Spring Boot; convenção simples de nomeação/versão.
- Demonstra domínio de SQL (relevante para o objetivo de portfólio).

### Negativas / custos
- Forward-only: reverter uma migration exige uma migration nova de compensação (não há `undo` na edição community).
- SQL específico de Postgres nas migrations — não portável para outro SGBD sem reescrever (aceitável: somos Postgres-only por decisão).

### Mitigações
- Como cada serviço tem seu banco, as migrations ficam isoladas por serviço (`db/migration` de cada módulo) — sem coordenação cruzada.
- Convenção de nomeação versionada (`V<n>__<descricao>.sql`) revisada em PR, com o CI aplicando as migrations nos testes de integração (Testcontainers).
