# Payment Reconciliation Engine — Contexto

Glossário do domínio de conciliação de pagamentos. Contexto único (não há `CONTEXT-MAP.md`). Termos canônicos em **inglês** (como aparecem no código e nos eventos); definições em **pt-BR**.

## Linguagem

**Source**:
A origem *do dado* de uma transação: `GATEWAY`, `BANK_STATEMENT` ou `INTERNAL_ORDER`. É de onde o registro veio.
_Evitar_: origem, fonte de dados (ambíguo com `Producer`)

**Producer**:
O *serviço* que emitiu um evento (ex.: `ingestion-service`). Deliberadamente distinto de `Source` — um é o serviço emissor, o outro é a origem do dado.
_Evitar_: emissor, fonte (colide com `Source`)

**Leg** (perna):
Um registro normalizado de **uma** `Source` para uma transação. Uma transação tem várias legs (uma por fonte esperada), que chegam em momentos diferentes.
_Evitar_: registro, entrada, linha

**Matching Key**:
A chave que agrupa as legs da mesma transação: a `externalReference` (só ela — ver ADR-0009). `amount`/`transactionDate` ficam de fora da chave para poderem ser *comparados* entre as legs.
_Evitar_: chave de agrupamento, chave de correlação

**Reconciliation Case** (caso de conciliação):
O grupo de legs com a mesma `Matching Key`, avaliado como um todo para produzir um `Reconciliation Status`. É reavaliado a cada nova leg que chega.
_Evitar_: grupo, conciliação, transação

**Reconciliation Status**:
O estado *atual* de um `Reconciliation Case`: `MATCHED`, `DIVERGENT`, `MISSING` ou `DUPLICATE`. Inclui o estado de sucesso (`MATCHED`).
_Evitar_: resultado, situação

**Divergence Type**:
O *tipo de problema* de um caso não-`MATCHED`: `DIVERGENT`, `MISSING` ou `DUPLICATE`. É o `Reconciliation Status` **menos** o `MATCHED` — um problema nunca é "casado". São conceitos distintos de propósito.
_Evitar_: usar `Reconciliation Status` no lugar (um caso `MATCHED` não tem `Divergence Type`)
