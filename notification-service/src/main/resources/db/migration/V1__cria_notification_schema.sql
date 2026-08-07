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
