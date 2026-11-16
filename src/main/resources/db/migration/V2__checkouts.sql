-- One row per checkout key (cart id + cart version, BR-ORD-05, ADR 0007). The key decides every race: placement and
-- expiry insert under it with ON CONFLICT DO NOTHING. A rejected checkout is stored too (decided 2026-11-16), so every
-- copy of the command gets the same answer. id = checkoutId, random (a key-derived id would collide on the primary key
-- instead of the unique key in a race). No personal data: the delivery address is not stored here.
CREATE TABLE checkouts
(
    id           UUID        NOT NULL PRIMARY KEY,
    customer_id  UUID        NOT NULL,
    cart_id      UUID        NOT NULL,
    cart_version BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,
    -- SHA-256 (hex) of the command content without eventId, occurredAt and requestedAt (same key + other hash ->
    -- CONTENT_MISMATCH). Computed over personal data (the delivery address); not reversible.
    content_hash CHAR(64)    NOT NULL,
    reasons      JSONB,
    -- eventId of the first PlaceCheckout of this key.
    causation_id UUID        NOT NULL,
    -- null when the command carried no valid requestedAt (stored as INVALID_REQUEST).
    requested_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_checkouts_cart UNIQUE (cart_id, cart_version),
    CONSTRAINT ck_checkouts_status CHECK (status IN ('PENDING', 'PLACED', 'REJECTED', 'STALE', 'EXPIRED')),
    CONSTRAINT ck_checkouts_reasons CHECK (status NOT IN ('REJECTED', 'STALE', 'EXPIRED') OR reasons IS NOT NULL)
);
