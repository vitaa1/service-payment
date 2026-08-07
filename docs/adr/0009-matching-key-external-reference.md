# ADR-0009: Matching key = externalReference

- **Status:** Aceito
- **Data:** 2026-08-06

## Contexto

O `reconciliation-service` agrupa os registros normalizados de fontes diferentes num `reconciliation_case` por uma **matching key**. O `architecture.md §5` propôs (como "proposta inicial"):

```
matchingKey = externalReference | amount | transactionDate
```

Mas a mesma seção define `DIVERGENT` como "fontes presentes, mas **`amount`/`currency`/`transactionDate` não conferem** entre elas". Há uma contradição interna: se `amount` e `transactionDate` fazem parte da **chave de agrupamento**, dois registros da mesma transação com valores divergentes recebem chaves **diferentes**, caem em **casos diferentes**, e a divergência de `amount`/`date` **nunca é detectada** — cada caso parece apenas `MISSING`.

## Decisão

A matching key é **apenas a `externalReference`**:

```
matchingKey = externalReference
```

A `externalReference` é, pela definição da própria spec, "o identificador comum que as três fontes carregam para a mesma transação". É o único campo garantidamente igual entre as fontes. Agrupando só por ele, `amount`/`currency`/`transactionDate` passam a ser **comparados dentro do caso** — que é exatamente o que habilita a detecção de `DIVERGENT`.

## Alternativas consideradas

- **`externalReference | amount | transactionDate`** (a proposta original). Rejeitada: coloca na chave justamente os campos que precisam ser comparados, impossibilitando a detecção de divergência de valor/data.
- **`externalReference | transactionDate`**. Rejeitada: a `transactionDate` também pode divergir entre fontes (é um campo de `DIVERGENT`), então não pode entrar na chave.

## Consequências

### Positivas
- A detecção de `DIVERGENT` (o caso de uso central do serviço) passa a funcionar.
- Chave mais simples e estável.

### Negativas / custos
- O campo `matchingKey` dos eventos (`ReconciliationCompleted`, `DivergenceDetected`) passa a carregar só a `externalReference` (ex.: `"chg_9f8e7d6c5b4a"`), não mais a string composta dos exemplos antigos.
- Se duas transações genuinamente distintas compartilhassem uma `externalReference`, seriam agrupadas erroneamente — mas isso contraria a definição de `externalReference` (id único da transação), então não deve ocorrer.

### Mitigações
- `architecture.md §5`, os exemplos de `docs/events/` e a entrada "Matching Key" do `CONTEXT.md` foram atualizados para refletir a chave = `externalReference`.
