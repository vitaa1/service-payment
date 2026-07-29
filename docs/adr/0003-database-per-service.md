# ADR-0003: Database per Service e escopo do módulo common-events

- **Status:** Aceito
- **Data:** 2026-07-29

## Contexto

Com microsserviços ([ADR-0001](0001-adocao-de-microsservicos.md)) comunicando-se por eventos ([ADR-0002](0002-comunicacao-hibrida-mensageria-rest.md)), falta decidir como cada serviço persiste seus dados. Um banco compartilhado entre serviços é a forma mais rápida de destruir o desacoplamento conquistado: mudanças de schema passam a exigir coordenação entre times, e os serviços voltam a se acoplar pela estrutura das tabelas em vez de pelos contratos de evento.

Além disso, escolhemos reutilizar os **contratos de evento** por meio de um módulo compartilhado (`common-events`). Isso introduz um acoplamento por biblioteca que precisa ser deliberadamente contido, para não virar uma porta dos fundos que reintroduz o acoplamento que a mensageria eliminou.

## Decisão

1. **Database per Service.** Cada serviço tem seu próprio banco PostgreSQL (`ingestion_db`, `reconciliation_db`, `notification_db`, `report_db`). Um serviço acessa **exclusivamente** seu banco. Não há chaves estrangeiras nem *joins* cruzando bancos; referências a dados de outro serviço são feitas por identificadores lógicos (ex.: `case_id` no `notification_log`).

2. **Escopo estrito do `common-events`.** O módulo compartilhado contém **apenas**:
   - Records/POJOs do **envelope** e dos **payloads** dos eventos.
   - Constantes de roteamento (nomes de exchanges e routing keys).

   É **proibido** no `common-events`: entidades JPA, lógica de negócio, DTOs de REST, dependências de Spring Data/Web. A dependência permitida é apenas anotações de serialização (Jackson).

## Alternativas consideradas

- **Banco único compartilhado.** Simples no começo, mas acopla os serviços pelo schema, transforma migrações em evento coordenado e viola o princípio de autonomia dos microsserviços. Descartado.
- **Schemas separados no mesmo servidor Postgres.** Um meio-termo operacionalmente mais barato. Aceitável, mas escolhemos bancos distintos para deixar a fronteira inequívoca na documentação e no docker-compose (o custo extra é irrelevante no ambiente local).
- **Contratos duplicados por serviço (sem `common-events`).** Máxima autonomia — cada serviço define suas próprias classes de evento. Foi a alternativa concorrente séria (ver trade-off abaixo). Optamos pelo módulo compartilhado por ser mais legível em um portfólio e reduzir duplicação, **contendo** o acoplamento via o escopo estrito acima.

## Consequências

### Positivas
- Autonomia real de dados: cada serviço evolui seu schema sem coordenação.
- A fronteira entre serviços é o **contrato de evento**, não a estrutura de tabelas.
- `common-events` elimina divergência de contrato entre produtor e consumidor e serve de documentação executável.

### Negativas / custos
- **Nenhuma transação distribuída** e nenhum *join* entre serviços — consultas que juntam dados de vários serviços exigem uma projeção (resolvido no report via [ADR-0004](0004-cqrs-no-report-service.md)).
- **Duplicação controlada de dados** entre bancos (ex.: `amount` aparece em `reconciliation_db` e na projeção do `report_db`).
- O `common-events` cria um **acoplamento por biblioteca**: uma mudança incompatível no contrato afeta todos que dependem dele. Isso contradiz *parcialmente* o ideal de autonomia total dos microsserviços — é um trade-off consciente.

### Mitigações
- **Versionamento de contrato**: mudanças incompatíveis criam um novo tipo de evento (ou campo novo opcional), nunca alteram silenciosamente um existente. O campo `eventType` no envelope carrega a versão quando necessário.
- **Escopo estrito** do `common-events` (só contratos) impede que o acoplamento vaze para regra de negócio ou persistência.
- Consistência entre bancos é reconciliada por eventos, com idempotência garantindo convergência.
