-- reconciliation_case: o grupo avaliado, uma linha por matching key (= externalReference, ADR-0009).
-- O lock pessimista da reavaliação incide nesta linha (ADR-0010).
CREATE TABLE reconciliation_case (
    id                 UUID          PRIMARY KEY,
    matching_key       VARCHAR(255)  NOT NULL UNIQUE,
    status             VARCHAR(20)   NOT NULL,
    version            INT           NOT NULL DEFAULT 1,
    divergence_details JSONB,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- normalized_record: cada perna canônica recebida. Dedup por event_id (ADR-0002).
-- external_reference É a matching key (ADR-0009); o vínculo com o caso é o case_id.
CREATE TABLE normalized_record (
    id                 UUID          PRIMARY KEY,
    event_id           UUID          NOT NULL UNIQUE,
    case_id            UUID          NOT NULL REFERENCES reconciliation_case(id),
    source             VARCHAR(20)   NOT NULL,
    external_reference VARCHAR(255)  NOT NULL,
    amount             NUMERIC(19,4) NOT NULL,
    currency           VARCHAR(3)    NOT NULL,
    transaction_date   DATE          NOT NULL,
    trace_id           UUID          NOT NULL,
    received_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_normalized_record_case ON normalized_record (case_id);

-- outbox: idêntico ao do ingestion (ADR-0006; duplicado por rule of three, ADR-0010).
CREATE TABLE outbox (
    id           BIGSERIAL     PRIMARY KEY,
    event_id     UUID          NOT NULL UNIQUE,
    event_type   VARCHAR(50)   NOT NULL,
    routing_key  VARCHAR(100)  NOT NULL,
    trace_id     UUID          NOT NULL,
    payload      JSONB         NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INT           NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_pendentes ON outbox (id) WHERE published_at IS NULL;
