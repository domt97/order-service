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

    @org.springframework.beans.factory.annotation.Autowired
    private org.flywaydb.core.Flyway flyway;

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    @Test
    @DisplayName("BR-ORD-15: Flyway migrates through the application DataSource (logServerErrorDetail=false)")
    void flywayUsesTheApplicationDataSource() {
        assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
    }

    @Test
    @DisplayName("V1 to V3 applied into schema orders, with its history table")
    void schemaAndHistoryInOrders() {
        assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'orders'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'orders' AND table_name IN ('flyway_schema_history', 'outbox_events', 'checkouts', "
                + "'orders', 'order_lines', 'store_day_counters', 'order_transitions')"))
                .isEqualTo(7);
        assertThat(count("SELECT count(*) FROM orders.flyway_schema_history WHERE version IN ('1', '2', '3') AND success"))
                .isEqualTo(3);
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
    @DisplayName("checkouts: status CHECK, reasons required for REJECTED/STALE/EXPIRED, reserve_due_at for PENDING")
    void checkoutChecks() {
        String insert = "INSERT INTO checkouts (id, customer_id, cart_id, cart_version, status, content_hash, reasons, "
                + "causation_id, created_at, reserve_due_at) "
                + "VALUES (?, ?, ?, 1, ?, repeat('a', 64), CAST(? AS jsonb), ?, now(), now() + interval '150 seconds')";
        String noDue = "INSERT INTO checkouts (id, customer_id, cart_id, cart_version, status, content_hash, "
                + "causation_id, created_at) VALUES (?, ?, ?, 1, 'PENDING', repeat('a', 64), ?, now())";
        assertThatThrownBy(() -> jdbc.update(noDue, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DONE",
                "[]", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "REJECTED",
                null, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PENDING", null,
                UUID.randomUUID())).isEqualTo(1);
    }

    @Test
    @DisplayName("orders: due time required in CONFIRM_SENT/RELEASE_SENT, number required once PLACED, lines >= 0")
    void orderChecks() {
        UUID checkout = UUID.randomUUID();
        jdbc.update("INSERT INTO checkouts (id, customer_id, cart_id, cart_version, status, content_hash, "
                + "causation_id, created_at, reserve_due_at) VALUES (?, ?, ?, 1, 'PENDING', repeat('a', 64), ?, now(), "
                + "now())", checkout, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String insert = "INSERT INTO orders (id, checkout_id, customer_id, merchant_id, store_id, store_code, "
                + "local_date, order_seq, order_number, status, stock_state, stock_due_at, currency, items_total, "
                + "delivery_quote_id, recipient_name, phone, line1, city, country, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 'HCM01', DATE '2026-11-21', ?, ?, ?, ?, ?, 'VND', 0, ?, 'r', 'p', 'l', 'c', "
                + "'VN', now(), now())";
        java.util.function.Function<Object[], Integer> order = a -> jdbc.update(insert, UUID.randomUUID(), checkout,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), a[0], a[1], a[2], a[3], a[4],
                UUID.randomUUID());
        // RELEASE_SENT without a due time
        assertThatThrownBy(() -> order.apply(new Object[]{null, null, "REJECTED", "RELEASE_SENT", null}))
                .isInstanceOf(DataIntegrityViolationException.class);
        // PLACED without a number
        assertThatThrownBy(() -> order.apply(new Object[]{null, null, "PLACED", "CONFIRM_SENT",
                java.sql.Timestamp.from(java.time.Instant.now())})).isInstanceOf(DataIntegrityViolationException.class);
        // seq without number
        assertThatThrownBy(() -> order.apply(new Object[]{1, null, "PENDING", "RESERVE_SENT", null}))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID ok = UUID.randomUUID();
        assertThat(jdbc.update(insert, ok, checkout, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                null, "PENDING", "RESERVE_SENT", null, UUID.randomUUID())).isEqualTo(1);
        String line = "INSERT INTO order_lines (id, order_id, cart_line_id, product_id, sku, product_name, quantity, "
                + "unit_price, currency, line_total) VALUES (?, ?, ?, ?, ?, 'p', ?, ?, 'VND', ?)";
        assertThatThrownBy(() -> jdbc.update(line, UUID.randomUUID(), ok, UUID.randomUUID(), UUID.randomUUID(), "A",
                1, -1, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(line, UUID.randomUUID(), ok, UUID.randomUUID(), UUID.randomUUID(), "B",
                100, 1, 100)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(line, UUID.randomUUID(), ok, UUID.randomUUID(), UUID.randomUUID(), "C", 1, 0, 0))
                .isEqualTo(1);
    }
}
