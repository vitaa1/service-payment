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
