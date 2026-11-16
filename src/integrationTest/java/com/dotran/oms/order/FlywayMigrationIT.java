package com.dotran.oms.order;

import com.dotran.oms.order.support.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flyway with the settings of application.yaml on an empty database: everything lands in schema {@code orders}. */
class FlywayMigrationIT extends PostgresIT {

    @Test
    @DisplayName("V1 and V2 applied into schema orders, with its history table")
    void schemaAndHistoryInOrders() {
        assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'orders'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'orders' AND table_name IN ('flyway_schema_history', 'outbox_events', 'checkouts')"))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM orders.flyway_schema_history WHERE version IN ('1', '2') AND success"))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("orders");
    }

    @Test
    @DisplayName("nothing is created in schema public")
    void nothingInPublic() {
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")).isZero();
    }

    @Test
    @DisplayName("outbox scope CHECK: exactly one of customer_id and merchant_id")
    void outboxScope() {
        String insert = "INSERT INTO outbox_events (id, merchant_id, customer_id, aggregate_type, aggregate_id, "
                + "event_type, payload, created_at) VALUES (?, ?, ?, 'Checkout', ?, 'X', '{}', now())";
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), null, null, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID())).isEqualTo(1);
    }

    @Test
    @DisplayName("checkouts: status CHECK and reasons required for REJECTED/STALE/EXPIRED")
    void checkoutChecks() {
        String insert = "INSERT INTO checkouts (id, customer_id, cart_id, cart_version, status, content_hash, reasons, "
                + "causation_id, created_at) VALUES (?, ?, ?, 1, ?, repeat('a', 64), CAST(? AS jsonb), ?, now())";
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DONE",
                "[]", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "REJECTED",
                null, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PENDING", null,
                UUID.randomUUID())).isEqualTo(1);
    }
}
