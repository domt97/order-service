package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.application.repository.OrderRepository;
import com.dotran.oms.order.application.repository.OrderTransition;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.OrderStatus;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import com.dotran.oms.order.domain.valueobject.DeliverySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.dotran.oms.order.infrastructure.persistence.JdbcValues.instant;
import static com.dotran.oms.order.infrastructure.persistence.JdbcValues.offset;
import static com.dotran.oms.order.infrastructure.persistence.JdbcValues.uuidArray;

/**
 * Orders, lines and history with plain JDBC. Reads by id filter by merchant (and store), reads by checkout by customer
 * (system rule 4); {@link #lockDueStockCommands} is a system-wide job read by design. Lines are read only with their
 * order. Delivery data is never put in an exception message here (BR-ORD-15).
 */
@PersistenceAdapter
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements OrderRepository {

    private static final String COLUMNS = """
            id, checkout_id, customer_id, merchant_id, store_id, store_code, local_date, order_seq, order_number,
            status, stock_state, reserve_rejection, reserve_expires_at, stock_due_at, stock_attempts, release_restock,
            release_reason, currency, items_total, delivery_quote_id, delivery_fee_amount, delivery_fee_currency,
            recipient_name, phone, line1, line2, ward, district, city, postal_code, country, placed_at, confirmed_at,
            created_at, updated_at
            """;
    private static final String INSERT_ORDER = """
            INSERT INTO orders (id, checkout_id, customer_id, merchant_id, store_id, store_code, local_date, status,
                                stock_state, stock_attempts, currency, items_total, delivery_quote_id, recipient_name,
                                phone, line1, line2, ward, district, city, postal_code, country, created_at,
                                updated_at)
            VALUES (:id, :checkoutId, :customerId, :merchantId, :storeId, :storeCode, :localDate, :status,
                    :stockState, 0, :currency, :itemsTotal, :deliveryQuoteId, :recipientName, :phone, :line1, :line2,
                    :ward, :district, :city, :postalCode, :country, :createdAt, :updatedAt)
            """;
    private static final String INSERT_LINE = """
            INSERT INTO order_lines (id, order_id, cart_line_id, product_id, sku, product_name, sku_name, quantity,
                                     unit_price, currency, line_total)
            VALUES (:id, :orderId, :cartLineId, :productId, :sku, :productName, :skuName, :quantity, :unitPrice,
                    :currency, :lineTotal)
            """;
    private static final String INSERT_HISTORY = """
            INSERT INTO order_transitions (order_id, from_status, to_status, actor, reason, at)
            VALUES (:orderId, :fromStatus, :toStatus, :actor, :reason, :at)
            """;
    private static final String FIND_SCOPED = """
            SELECT id, checkout_id, customer_id FROM orders
            WHERE id = :id AND merchant_id = :merchantId AND store_id = :storeId
            """;
    private static final String BY_CHECKOUT = "SELECT " + COLUMNS + """
            FROM orders WHERE checkout_id = :checkoutId AND customer_id = :customerId
            ORDER BY id
            """;
    private static final String LOCK_BY_CHECKOUT = BY_CHECKOUT + "FOR UPDATE\n";
    private static final String LINES = """
            SELECT id, order_id, cart_line_id, product_id, sku, product_name, sku_name, quantity, unit_price, currency,
                   line_total
            FROM order_lines WHERE order_id = ANY (CAST(:ids AS uuid[]))
            ORDER BY order_id, sku
            """;
    private static final String TRANSITION = """
            UPDATE orders SET
                status = :toStatus,
                stock_state = :toStock,
                stock_due_at = CASE WHEN :keepDue THEN stock_due_at ELSE CAST(:stockDueAt AS timestamptz) END,
                stock_attempts = CASE WHEN :resetAttempts THEN 0 ELSE stock_attempts END,
                order_seq = COALESCE(CAST(:orderSeq AS int), order_seq),
                order_number = COALESCE(CAST(:orderNumber AS varchar), order_number),
                placed_at = COALESCE(CAST(:placedAt AS timestamptz), placed_at),
                confirmed_at = COALESCE(CAST(:confirmedAt AS timestamptz), confirmed_at),
                reserve_rejection = COALESCE(CAST(:reserveRejection AS jsonb), reserve_rejection),
                reserve_expires_at = COALESCE(CAST(:reserveExpiresAt AS timestamptz), reserve_expires_at),
                release_restock = COALESCE(CAST(:releaseRestock AS boolean), release_restock),
                release_reason = COALESCE(CAST(:releaseReason AS varchar), release_reason),
                updated_at = :now
            WHERE id = :id AND merchant_id = :merchantId AND checkout_id = :checkoutId
              AND status = :fromStatus AND stock_state = :fromStock
            """;
    private static final String DUE = "SELECT " + COLUMNS + """
            FROM orders
            WHERE stock_state IN ('CONFIRM_SENT', 'RELEASE_SENT') AND stock_due_at <= :now
              AND NOT (id = ANY (CAST(:excluded AS uuid[])))
            ORDER BY stock_due_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;
    private static final String RELOCK_DUE = "SELECT " + COLUMNS + """
            FROM orders
            WHERE id = :id AND merchant_id = :merchantId AND stock_state IN ('CONFIRM_SENT', 'RELEASE_SENT')
              AND stock_due_at <= :now
            FOR UPDATE SKIP LOCKED
            """;
    // Postgres evaluates every SET expression on the old row: stock_attempts + 1 is the new count. The exponent is
    // capped at 20 (2^20 × initial is above max for the shipped values): power(2, >= 1024) overflows double precision
    // (22003) before LEAST applies, and a command failing ~7 days at the cap would then never be re-sent again.
    private static final String RESCHEDULE = """
            UPDATE orders SET
                stock_attempts = stock_attempts + 1,
                stock_due_at = CAST(:now AS timestamptz) + make_interval(secs => LEAST(
                    CAST(:initialSeconds AS double precision) * power(2, LEAST(stock_attempts + 1, 20)),
                    CAST(:maxSeconds AS double precision))),
                updated_at = :now
            WHERE id = :id AND merchant_id = :merchantId AND stock_state IN ('CONFIRM_SENT', 'RELEASE_SENT')
            RETURNING stock_attempts, stock_state
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcValues values;

    @Override
    public void insertAll(List<Order> orders) {
        if (orders.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_ORDER, orders.stream().map(OrderPersistenceAdapter::orderParams)
                .toArray(SqlParameterSource[]::new));
        jdbc.batchUpdate(INSERT_LINE, orders.stream()
                .flatMap(o -> o.lines().stream().map(l -> lineParams(o, l)))
                .toArray(SqlParameterSource[]::new));
        jdbc.batchUpdate(INSERT_HISTORY, orders.stream()
                .map(o -> history(o.id(), null, o.status(), "SYSTEM", "PLACEMENT", o.createdAt()))
                .toArray(SqlParameterSource[]::new));
    }

    @Override
    public Optional<OrderKey> findScoped(OrderId id, MerchantId merchantId, StoreId storeId) {
        return jdbc.query(FIND_SCOPED, new MapSqlParameterSource()
                        .addValue("id", id.getValue())
                        .addValue("merchantId", merchantId.getValue())
                        .addValue("storeId", storeId.getValue()),
                (rs, i) -> new OrderKey(OrderId.of(rs.getObject("id", UUID.class)),
                        CheckoutId.of(rs.getObject("checkout_id", UUID.class)),
                        CustomerId.of(rs.getObject("customer_id", UUID.class)))).stream().findFirst();
    }

    @Override
    public List<Order> findByCheckout(CheckoutId checkoutId, CustomerId customerId) {
        return withLines(jdbc.query(BY_CHECKOUT, byCheckout(checkoutId, customerId), (rs, i) -> row(rs)));
    }

    @Override
    public List<Order> lockByCheckout(CheckoutId checkoutId, CustomerId customerId) {
        return withLines(jdbc.query(LOCK_BY_CHECKOUT, byCheckout(checkoutId, customerId), (rs, i) -> row(rs)));
    }

    @Override
    public boolean transition(OrderTransition t) {
        int rows = jdbc.update(TRANSITION, new MapSqlParameterSource()
                .addValue("id", t.orderId().getValue())
                .addValue("merchantId", t.merchantId().getValue())
                .addValue("checkoutId", t.checkoutId().getValue())
                .addValue("fromStatus", t.fromStatus().name())
                .addValue("fromStock", t.fromStock().name())
                .addValue("toStatus", t.toStatus().name())
                .addValue("toStock", t.toStock().name())
                .addValue("stockDueAt", offset(t.stockDueAt()))
                .addValue("keepDue", t.toStock() == t.fromStock())
                .addValue("resetAttempts", t.toStock().requiresDueTime() && t.toStock() != t.fromStock())
                .addValue("orderSeq", t.orderSeq())
                .addValue("orderNumber", t.orderNumber())
                .addValue("placedAt", offset(t.placedAt()))
                .addValue("confirmedAt", offset(t.confirmedAt()))
                .addValue("reserveRejection", values.reasonsJson(t.reserveRejection()))
                .addValue("reserveExpiresAt", offset(t.reserveExpiresAt()))
                .addValue("releaseRestock", t.releaseRestock())
                .addValue("releaseReason", t.releaseReason())
                .addValue("now", offset(t.now())));
        if (rows == 1 && t.fromStatus() != t.toStatus()) {
            jdbc.update(INSERT_HISTORY, history(t.orderId(), t.fromStatus(), t.toStatus(), t.actor(), t.reason(),
                    t.now()));
        }
        return rows == 1;
    }

    @Override
    public List<Order> lockDueStockCommands(Instant now, int limit, Set<UUID> excluded) {
        return withLines(jdbc.query(DUE, new MapSqlParameterSource()
                .addValue("now", offset(now))
                .addValue("limit", limit)
                .addValue("excluded", uuidArray(excluded)), (rs, i) -> row(rs)));
    }

    @Override
    public Optional<Order> relockDue(OrderId id, MerchantId merchantId, Instant now) {
        return withLines(jdbc.query(RELOCK_DUE, new MapSqlParameterSource()
                .addValue("id", id.getValue())
                .addValue("merchantId", merchantId.getValue())
                .addValue("now", offset(now)), (rs, i) -> row(rs))).stream().findFirst();
    }

    @Override
    public Optional<Rescheduled> reschedule(OrderId id, MerchantId merchantId, Instant now, Duration initial,
                                            Duration max) {
        return jdbc.query(RESCHEDULE, new MapSqlParameterSource()
                        .addValue("id", id.getValue())
                        .addValue("merchantId", merchantId.getValue())
                        .addValue("now", offset(now))
                        .addValue("initialSeconds", (double) initial.toSeconds())
                        .addValue("maxSeconds", (double) max.toSeconds()),
                (rs, i) -> new Rescheduled(rs.getInt("stock_attempts"),
                        StockState.valueOf(rs.getString("stock_state")))).stream().findFirst();
    }

    private static MapSqlParameterSource byCheckout(CheckoutId checkoutId, CustomerId customerId) {
        return new MapSqlParameterSource()
                .addValue("checkoutId", checkoutId.getValue())
                .addValue("customerId", customerId.getValue());
    }

    private static MapSqlParameterSource orderParams(Order o) {
        DeliverySnapshot d = o.delivery();
        return new MapSqlParameterSource()
                .addValue("id", o.id().getValue())
                .addValue("checkoutId", o.checkoutId().getValue())
                .addValue("customerId", o.customerId().getValue())
                .addValue("merchantId", o.merchantId().getValue())
                .addValue("storeId", o.storeId().getValue())
                .addValue("storeCode", o.storeCode())
                .addValue("localDate", Date.valueOf(o.localDate()))
                .addValue("status", o.status().name())
                .addValue("stockState", o.stockState().name())
                .addValue("currency", o.currency())
                .addValue("itemsTotal", o.itemsTotal())
                .addValue("deliveryQuoteId", o.deliveryQuoteId())
                .addValue("recipientName", d.recipientName())
                .addValue("phone", d.phone())
                .addValue("line1", d.line1())
                .addValue("line2", d.line2())
                .addValue("ward", d.ward())
                .addValue("district", d.district())
                .addValue("city", d.city())
                .addValue("postalCode", d.postalCode())
                .addValue("country", d.country())
                .addValue("createdAt", offset(o.createdAt()))
                .addValue("updatedAt", offset(o.updatedAt()));
    }

    private static MapSqlParameterSource lineParams(Order o, OrderLine l) {
        return new MapSqlParameterSource()
                .addValue("id", l.id().getValue())
                .addValue("orderId", o.id().getValue())
                .addValue("cartLineId", l.cartLineId())
                .addValue("productId", l.productId().getValue())
                .addValue("sku", l.sku())
                .addValue("productName", l.productName())
                .addValue("skuName", l.skuName())
                .addValue("quantity", l.quantity())
                .addValue("unitPrice", l.unitPrice())
                .addValue("currency", l.currency())
                .addValue("lineTotal", l.lineTotal());
    }

    private static MapSqlParameterSource history(OrderId orderId, OrderStatus from, OrderStatus to, String actor,
                                                 String reason, Instant at) {
        return new MapSqlParameterSource()
                .addValue("orderId", orderId.getValue())
                .addValue("fromStatus", from == null ? null : from.name())
                .addValue("toStatus", to.name())
                .addValue("actor", actor)
                .addValue("reason", reason)
                .addValue("at", offset(at));
    }

    /** One query for the lines of all the given orders, attached in the orders' order. */
    private List<Order> withLines(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<OrderLine>> lines = new LinkedHashMap<>();
        rows.forEach(r -> lines.put(r.id(), new ArrayList<>()));
        jdbc.query(LINES, new MapSqlParameterSource("ids", uuidArray(lines.keySet())), rs -> {
            lines.get(rs.getObject("order_id", UUID.class)).add(new OrderLine(
                    OrderItemId.of(rs.getObject("id", UUID.class)),
                    rs.getObject("cart_line_id", UUID.class),
                    ProductId.of(rs.getObject("product_id", UUID.class)),
                    rs.getString("sku"),
                    rs.getString("product_name"),
                    rs.getString("sku_name"),
                    rs.getInt("quantity"),
                    rs.getBigDecimal("unit_price"),
                    rs.getString("currency"),
                    rs.getBigDecimal("line_total")));
        });
        return rows.stream().map(r -> r.toOrder(lines.get(r.id()))).toList();
    }

    /** Reads one order row; the {@link Order} is built once its lines are known (an order has at least one line). */
    private Row row(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        CheckoutId checkoutId = CheckoutId.of(rs.getObject("checkout_id", UUID.class));
        CustomerId customerId = CustomerId.of(rs.getObject("customer_id", UUID.class));
        MerchantId merchantId = MerchantId.of(rs.getObject("merchant_id", UUID.class));
        StoreId storeId = StoreId.of(rs.getObject("store_id", UUID.class));
        String storeCode = rs.getString("store_code");
        LocalDate localDate = rs.getDate("local_date").toLocalDate();
        Integer seq = rs.getObject("order_seq", Integer.class);
        String number = rs.getString("order_number");
        OrderStatus status = OrderStatus.valueOf(rs.getString("status"));
        StockState stock = StockState.valueOf(rs.getString("stock_state"));
        var rejection = values.readReasons(rs.getString("reserve_rejection"));
        Instant reserveExpiresAt = instant(rs.getObject("reserve_expires_at", OffsetDateTime.class));
        Instant stockDueAt = instant(rs.getObject("stock_due_at", OffsetDateTime.class));
        int attempts = rs.getInt("stock_attempts");
        Boolean restock = rs.getObject("release_restock", Boolean.class);
        String releaseReason = rs.getString("release_reason");
        String currency = rs.getString("currency");
        BigDecimal itemsTotal = rs.getBigDecimal("items_total");
        UUID quoteId = rs.getObject("delivery_quote_id", UUID.class);
        BigDecimal feeAmount = rs.getBigDecimal("delivery_fee_amount");
        String feeCurrency = rs.getString("delivery_fee_currency");
        DeliverySnapshot delivery = new DeliverySnapshot(rs.getString("recipient_name"), rs.getString("phone"),
                rs.getString("line1"), rs.getString("line2"), rs.getString("ward"), rs.getString("district"),
                rs.getString("city"), rs.getString("postal_code"), rs.getString("country"));
        Instant placedAt = instant(rs.getObject("placed_at", OffsetDateTime.class));
        Instant confirmedAt = instant(rs.getObject("confirmed_at", OffsetDateTime.class));
        Instant createdAt = instant(rs.getObject("created_at", OffsetDateTime.class));
        Instant updatedAt = instant(rs.getObject("updated_at", OffsetDateTime.class));
        return new Row(id, lines -> new Order(OrderId.of(id), checkoutId, customerId, merchantId, storeId, storeCode,
                localDate, seq, number, status, stock, rejection, reserveExpiresAt, stockDueAt, attempts, restock,
                releaseReason, currency, itemsTotal, quoteId, feeAmount, feeCurrency, delivery, lines, placedAt,
                confirmedAt, createdAt, updatedAt));
    }

    private record Row(UUID id, Function<List<OrderLine>, Order> build) {
        Order toOrder(List<OrderLine> lines) {
            return build.apply(lines);
        }
    }
}
