-- Checkout placement (ADR 0007): one order per store, its lines, the stores' daily counters and the order history.
-- No backfill: before V3 only REJECTED and STALE checkouts exist, so the new CHECK holds on every row.

-- A PENDING checkout waits for inventory's reserve replies until reserve_due_at (150 s, D3); the timeout job reads
-- the partial index. created_at of PENDING rows feeds the oldest-PENDING gauge (a defer never moves it).
ALTER TABLE checkouts
    ADD COLUMN reserve_due_at TIMESTAMPTZ,
    ADD COLUMN placed_at      TIMESTAMPTZ,
    ADD COLUMN updated_at     TIMESTAMPTZ,
    ADD CONSTRAINT ck_checkouts_pending_due CHECK (status <> 'PENDING' OR reserve_due_at IS NOT NULL);
CREATE INDEX ix_checkouts_pending_due ON checkouts (reserve_due_at) WHERE status = 'PENDING';
CREATE INDEX ix_checkouts_pending_created ON checkouts (created_at) WHERE status = 'PENDING';

-- Merchant-owned (merchant_id) and carrying its checkout's customer_id (system rule 4). The delivery columns are
-- personal data (BR-ORD-15): never logged, never in an event. Lengths count characters (code points), as the
-- PlaceCheckout validator does. delivery_fee_* stay NULL until delivery quotes exist (D2).
CREATE TABLE orders
(
    id                    UUID          NOT NULL PRIMARY KEY,
    checkout_id           UUID          NOT NULL REFERENCES checkouts (id),
    customer_id           UUID          NOT NULL,
    merchant_id           UUID          NOT NULL,
    store_id              UUID          NOT NULL,
    store_code            VARCHAR(20)   NOT NULL,
    -- The store's local day at placement (D8): daily cap and order number.
    local_date            DATE          NOT NULL,
    order_seq             INT,
    order_number          VARCHAR(40),
    status                VARCHAR(20)   NOT NULL,
    stock_state           VARCHAR(20)   NOT NULL,
    -- Inventory's reasons for a rejected reserve, already in CheckoutRejected codes.
    reserve_rejection     JSONB,
    reserve_expires_at    TIMESTAMPTZ,
    -- When the re-send job next sends the open Confirm or Release (CONFIRM_SENT, RELEASE_SENT only).
    stock_due_at          TIMESTAMPTZ,
    stock_attempts        INT           NOT NULL DEFAULT 0,
    release_restock       BOOLEAN,
    release_reason        VARCHAR(50),
    currency              CHAR(3)       NOT NULL,
    items_total           NUMERIC(19, 4) NOT NULL,
    delivery_quote_id     UUID          NOT NULL,
    delivery_fee_amount   NUMERIC(19, 4),
    delivery_fee_currency CHAR(3),
    recipient_name        VARCHAR(255)  NOT NULL,
    phone                 VARCHAR(32)   NOT NULL,
    line1                 VARCHAR(255)  NOT NULL,
    line2                 VARCHAR(255),
    ward                  VARCHAR(100),
    district              VARCHAR(100),
    city                  VARCHAR(100)  NOT NULL,
    postal_code           VARCHAR(20),
    country               CHAR(2)       NOT NULL,
    placed_at             TIMESTAMPTZ,
    confirmed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_orders_checkout_store UNIQUE (checkout_id, store_id),
    CONSTRAINT uk_orders_store_number UNIQUE (store_id, order_number),
    CONSTRAINT uk_orders_id_merchant UNIQUE (id, merchant_id),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'DELIVERING',
                                                  'DELIVERED', 'COMPLETED', 'REJECTED', 'CANCELLED',
                                                  'DELIVERY_FAILED')),
    CONSTRAINT ck_orders_stock_state CHECK (stock_state IN ('RESERVE_SENT', 'RESERVED', 'RESERVE_REJECTED',
                                                            'CONFIRM_SENT', 'CONFIRMED', 'RELEASE_SENT', 'RELEASED')),
    CONSTRAINT ck_orders_stock_due CHECK (stock_state NOT IN ('CONFIRM_SENT', 'RELEASE_SENT')
        OR stock_due_at IS NOT NULL),
    CONSTRAINT ck_orders_number CHECK ((order_seq IS NULL) = (order_number IS NULL)),
    CONSTRAINT ck_orders_numbered CHECK (status IN ('PENDING', 'REJECTED') OR order_number IS NOT NULL),
    CONSTRAINT ck_orders_attempts CHECK (stock_attempts >= 0),
    CONSTRAINT ck_orders_items_total CHECK (items_total >= 0)
);
CREATE INDEX ix_orders_stock_due ON orders (stock_due_at) WHERE stock_state IN ('CONFIRM_SENT', 'RELEASE_SENT');

-- id = the orderItemId sent to inventory (new per order: cart line ids repeat across cart versions). No merchant_id:
-- lines are read only through their merchant-checked, immutable order. Prices >= 0 (Q1 backstop).
CREATE TABLE order_lines
(
    id           UUID           NOT NULL PRIMARY KEY,
    order_id     UUID           NOT NULL REFERENCES orders (id),
    cart_line_id UUID           NOT NULL,
    product_id   UUID           NOT NULL,
    sku          VARCHAR(100)   NOT NULL,
    product_name VARCHAR(255)   NOT NULL,
    sku_name     VARCHAR(255),
    quantity     INT            NOT NULL,
    unit_price   NUMERIC(19, 4) NOT NULL,
    currency     CHAR(3)        NOT NULL,
    line_total   NUMERIC(19, 4) NOT NULL,
    CONSTRAINT uk_order_lines_sku UNIQUE (order_id, sku),
    CONSTRAINT uk_order_lines_cart_line UNIQUE (order_id, cart_line_id),
    CONSTRAINT ck_order_lines_quantity CHECK (quantity BETWEEN 1 AND 99),
    CONSTRAINT ck_order_lines_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_lines_line_total CHECK (line_total >= 0)
);

-- One row per store and local day (ADR 0007 Counters): placed_count is the daily cap (BR-STO-07), last_seq the order
-- number sequence (BR-ORD-10). Always changed one row per statement, in store id order.
CREATE TABLE store_day_counters
(
    store_id     UUID NOT NULL,
    local_date   DATE NOT NULL,
    merchant_id  UUID NOT NULL,
    placed_count INT  NOT NULL,
    last_seq     INT  NOT NULL,
    PRIMARY KEY (store_id, local_date),
    CONSTRAINT ck_store_day_counters_placed CHECK (placed_count >= 0),
    CONSTRAINT ck_store_day_counters_seq CHECK (last_seq >= 0)
);

-- Every status change with actor, reason and time (BR-ORD-06). from_status is NULL for the first row.
CREATE TABLE order_transitions
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    UUID        NOT NULL REFERENCES orders (id),
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    actor       VARCHAR(20) NOT NULL,
    reason      VARCHAR(50),
    at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_order_transitions_order ON order_transitions (order_id);
