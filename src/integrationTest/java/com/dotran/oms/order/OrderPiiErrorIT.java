package com.dotran.oms.order;

import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.repository.OrderRepository;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.support.Orders;
import com.dotran.oms.order.support.PostgresIT;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * BR-ORD-15: a failing write of an order must not put the delivery data into an exception's text (and so into a log
 * line or a DLT header). Positive control first: on a plain connection (pgjdbc default, server detail on) the same
 * failures do carry the phone and name, so the clean result on the application DataSource proves the setting, not a
 * test that cannot see the leak.
 */
class OrderPiiErrorIT extends PostgresIT {

    private static final String[] PII = {Orders.DELIVERY.phone(), Orders.DELIVERY.recipientName(),
            Orders.DELIVERY.line1()};
    private static final String INSERT = """
            INSERT INTO orders (id, checkout_id, customer_id, merchant_id, store_id, store_code, local_date, status,
                                stock_state, currency, items_total, delivery_quote_id, recipient_name, phone, line1,
                                city, country, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'HCM01', DATE '2026-11-21', 'PENDING', 'RESERVE_SENT', 'VND', ?, ?, ?, ?, ?, ?, 'VN',
                    now(), now())
            """;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CheckoutRepository checkouts;

    private final UUID customer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();

    /** Everything a logger could print: each cause's toString, suppressed ones, every SQLException's next ones, stack. */
    static String render(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> todo = new ArrayList<>(List.of(failure));
        while (!todo.isEmpty()) {
            Throwable t = todo.remove(0);
            if (t == null || !seen.add(t)) {
                continue;
            }
            text.append(t).append('\n');
            todo.add(t.getCause());
            todo.addAll(List.of(t.getSuppressed()));
            if (t instanceof SQLException sql) {
                todo.add(sql.getNextException());
            }
        }
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        return text.append(stack).toString();
    }

    static Set<String> sqlStates(Throwable failure) {
        Set<String> states = new java.util.HashSet<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> todo = new ArrayList<>(List.of(failure));
        while (!todo.isEmpty()) {
            Throwable t = todo.remove(0);
            if (t == null || !seen.add(t)) {
                continue;
            }
            todo.add(t.getCause());
            if (t instanceof SQLException sql) {
                if (sql.getSQLState() != null) {
                    states.add(sql.getSQLState());
                }
                todo.add(sql.getNextException());
            }
        }
        return states;
    }

    private Checkout checkout() {
        Checkout c = Orders.pendingCheckout(customer, Orders.now());
        checkouts.insertIfAbsent(c);
        return c;
    }

    /** One row: items_total -1 → 23514 (CHECK), or city null → 23502 (NOT NULL). */
    private void bind(PreparedStatement ps, Checkout c, BigDecimal total, String city) throws SQLException {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, c.id().getValue());
        ps.setObject(3, customer);
        ps.setObject(4, merchant);
        ps.setObject(5, UUID.randomUUID());
        ps.setBigDecimal(6, total);
        ps.setObject(7, UUID.randomUUID());
        ps.setString(8, Orders.DELIVERY.recipientName());
        ps.setString(9, Orders.DELIVERY.phone());
        ps.setString(10, Orders.DELIVERY.line1());
        ps.setString(11, city);
    }

    private Connection plainConnection() throws SQLException {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        return DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }

    private Throwable batchOnPlainConnection(Checkout c) throws SQLException {
        try (Connection conn = plainConnection(); PreparedStatement ps = conn.prepareStatement(INSERT)) {
            bind(ps, c, BigDecimal.ONE, "Ho Chi Minh");
            ps.addBatch();
            bind(ps, c, BigDecimal.valueOf(-1), "Ho Chi Minh");
            ps.addBatch();
            return catchThrowable(ps::executeBatch);
        }
    }

    private Throwable singleOnPlainConnection(Checkout c) throws SQLException {
        try (Connection conn = plainConnection(); PreparedStatement ps = conn.prepareStatement(INSERT)) {
            bind(ps, c, BigDecimal.ONE, null);
            return catchThrowable(ps::executeUpdate);
        }
    }

    @Test
    @DisplayName("positive control: a plain connection's 23514 batch and 23502 failures carry the delivery data")
    void positiveControl() throws Exception {
        Checkout c = checkout();
        Throwable batch = batchOnPlainConnection(c);
        assertThat(sqlStates(batch)).contains("23514");
        assertThat(render(batch)).contains(Orders.DELIVERY.phone());

        Throwable single = singleOnPlainConnection(c);
        assertThat(sqlStates(single)).contains("23502");
        assertThat(render(single)).contains(Orders.DELIVERY.phone()).contains(Orders.DELIVERY.recipientName());
    }

    @Test
    @DisplayName("the adapter's batch insert of 2 orders, the 2nd failing its CHECK (23514): no delivery data anywhere")
    void adapterBatch() {
        Checkout c = checkout();
        Instant now = c.createdAt();
        Order ok = Orders.pending(c.id(), customer, merchant, UUID.randomUUID(), now, Orders.line("A", "1000", 1));
        Order base = Orders.pending(c.id(), customer, merchant, UUID.randomUUID(), now, Orders.line("B", "1000", 1));
        Order negative = new Order(base.id(), base.checkoutId(), base.customerId(), base.merchantId(), base.storeId(),
                base.storeCode(), base.localDate(), null, null, base.status(), base.stockState(), null, null, null, 0,
                null, null, base.currency(), BigDecimal.valueOf(-1), base.deliveryQuoteId(), null, null,
                base.delivery(), base.lines(), null, null, now, now);

        Throwable failure = catchThrowable(() -> inTx(() -> {
            orders.insertAll(List.of(ok, negative));
            return null;
        }));

        assertThat(failure).isNotNull();
        assertThat(sqlStates(failure)).contains("23514");
        String text = render(failure);
        for (String value : PII) {
            assertThat(text).doesNotContain(value);
        }
        assertThat(count("SELECT count(*) FROM orders")).isZero();
    }

    @Test
    @DisplayName("a NOT NULL failure (23502) on the application DataSource: no delivery data anywhere")
    void notNull() {
        Checkout c = checkout();
        Throwable failure = catchThrowable(() -> jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT);
            bind(ps, c, BigDecimal.ONE, null);
            return ps;
        }));
        assertThat(sqlStates(failure)).contains("23502");
        String text = render(failure);
        for (String value : PII) {
            assertThat(text).doesNotContain(value);
        }
    }
}
