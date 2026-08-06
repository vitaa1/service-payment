-- raw_ingestion: payload cru de cada request, para auditoria e idempotência.
-- Ver docs/architecture.md §6.1, docs/ingestion/source-formats.md, ADR-0008.
CREATE TABLE raw_ingestion (
    id                 UUID         PRIMARY KEY,
    source             VARCHAR(20)  NOT NULL,
    raw_payload        JSONB        NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    validation_errors  JSONB,
    idempotency_key    VARCHAR(255) UNIQUE,
    trace_id           UUID         NOT NULL,
    published_event_id UUID,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- outbox: eventos a publicar, gravados na MESMA transação do estado de negócio (ADR-0006).
-- Um relay lê as pendentes (published_at IS NULL) e publica no RabbitMQ.
CREATE TABLE outbox (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    event_type   VARCHAR(50)  NOT NULL,
    routing_key  VARCHAR(100) NOT NULL,
    trace_id     UUID,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0
);

-- Índice parcial: o relay só varre as pendentes, em ordem de inserção.
CREATE INDEX idx_outbox_pendentes ON outbox (id) WHERE published_at IS NULL;
