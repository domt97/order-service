-- Transactional outbox of order-service (workspace system rules 2 and 8, ADR 0007). Debezium (connector order-outbox-cdc,
-- development repo) publishes every INSERT to the topic named by event_type, key aggregate_id, payload as the value and
-- correlation_context as a header. Rows are inserted and deleted in the same transaction, so the table stays empty:
-- no status column, no index. id = the payload's eventId (bound by the application).
-- Scope (system rule 4): a checkout reply belongs to the customer (customer_id), a stock command (later) to the
-- merchant (merchant_id); exactly one of them is set.
CREATE TABLE outbox_events
(
    id                  UUID         NOT NULL PRIMARY KEY,
    merchant_id         UUID,
    customer_id         UUID,
    aggregate_type      VARCHAR(50)  NOT NULL,
    aggregate_id        UUID         NOT NULL,
    event_type          VARCHAR(100) NOT NULL,
    payload             JSONB        NOT NULL,
    correlation_context JSONB        NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_outbox_events_scope CHECK (num_nonnulls(merchant_id, customer_id) = 1)
);
