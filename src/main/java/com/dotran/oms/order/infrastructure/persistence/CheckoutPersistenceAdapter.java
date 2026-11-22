package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dotran.oms.order.infrastructure.persistence.JdbcValues.instant;
import static com.dotran.oms.order.infrastructure.persistence.JdbcValues.offset;
import static com.dotran.oms.order.infrastructure.persistence.JdbcValues.uuidArray;

/**
 * Checkouts with plain JDBC: the insert is {@code ON CONFLICT (cart_id, cart_version) DO NOTHING} (the key decides every
 * race, ADR 0007) and the reasons are JSONB. Reads and writes filter by the customer (system rule 4);
 * {@link #lockNextDueReserve} and {@link #oldestPendingCreatedAt} are system-wide job reads by design.
 */
@PersistenceAdapter
@RequiredArgsConstructor
public class CheckoutPersistenceAdapter implements CheckoutRepository {

    private static final String COLUMNS = """
            id, customer_id, cart_id, cart_version, status, content_hash, reasons, causation_id, requested_at,
            created_at, reserve_due_at, placed_at, updated_at
            """;
    private static final String INSERT = """
            INSERT INTO checkouts (id, customer_id, cart_id, cart_version, status, content_hash, reasons, causation_id,
                                   requested_at, created_at, reserve_due_at, placed_at, updated_at)
            VALUES (:id, :customerId, :cartId, :cartVersion, :status, :contentHash, CAST(:reasons AS jsonb),
                    :causationId, :requestedAt, :createdAt, :reserveDueAt, :placedAt, :updatedAt)
            ON CONFLICT (cart_id, cart_version) DO NOTHING
            """;
    private static final String FIND = "SELECT " + COLUMNS + """
            FROM checkouts
            WHERE cart_id = :cartId AND cart_version = :cartVersion AND customer_id = :customerId
            """;
    private static final String LOCK = "SELECT " + COLUMNS + """
            FROM checkouts
            WHERE id = :id AND customer_id = :customerId
            FOR UPDATE
            """;
    private static final String MARK_PLACED = """
            UPDATE checkouts SET status = 'PLACED', placed_at = :now, updated_at = :now
            WHERE id = :id AND customer_id = :customerId AND status = 'PENDING'
            """;
    private static final String MARK_REJECTED = """
            UPDATE checkouts SET status = 'REJECTED', reasons = CAST(:reasons AS jsonb), updated_at = :now
            WHERE id = :id AND customer_id = :customerId AND status = 'PENDING'
            """;
    private static final String NEXT_DUE = "SELECT " + COLUMNS + """
            FROM checkouts
            WHERE status = 'PENDING' AND reserve_due_at <= :now AND NOT (id = ANY (CAST(:excluded AS uuid[])))
            ORDER BY reserve_due_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;
    private static final String DEFER = """
            UPDATE checkouts SET reserve_due_at = :until, updated_at = :now
            WHERE id = :id AND customer_id = :customerId AND status = 'PENDING'
            """;
    private static final String OLDEST_PENDING = "SELECT min(created_at) FROM checkouts WHERE status = 'PENDING'";

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcValues values;

    @Override
    public boolean insertIfAbsent(Checkout c) {
        int rows = jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("id", c.id().getValue())
                .addValue("customerId", c.customerId().getValue())
                .addValue("cartId", c.cartId().getValue())
                .addValue("cartVersion", c.cartVersion())
                .addValue("status", c.status().name())
                .addValue("contentHash", c.contentHash())
                .addValue("reasons", values.reasonsJson(c.reasons()))
                .addValue("causationId", c.causationId().getValue())
                .addValue("requestedAt", offset(c.requestedAt()))
                .addValue("createdAt", offset(c.createdAt()))
                .addValue("reserveDueAt", offset(c.reserveDueAt()))
                .addValue("placedAt", offset(c.placedAt()))
                .addValue("updatedAt", offset(c.updatedAt())));
        return rows == 1;
    }

    @Override
    public Optional<Checkout> findByKey(CartId cartId, long cartVersion, CustomerId customerId) {
        return jdbc.query(FIND, new MapSqlParameterSource()
                        .addValue("cartId", cartId.getValue())
                        .addValue("cartVersion", cartVersion)
                        .addValue("customerId", customerId.getValue()),
                (rs, i) -> map(rs)).stream().findFirst();
    }

    @Override
    public Optional<Checkout> lockForUpdate(CheckoutId id, CustomerId customerId) {
        return jdbc.query(LOCK, scope(id, customerId), (rs, i) -> map(rs)).stream().findFirst();
    }

    @Override
    public boolean markPlaced(CheckoutId id, CustomerId customerId, Instant now) {
        return jdbc.update(MARK_PLACED, scope(id, customerId).addValue("now", offset(now))) == 1;
    }

    @Override
    public boolean markRejected(CheckoutId id, CustomerId customerId, List<RejectionReason> reasons, Instant now) {
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("A rejected checkout needs at least one reason");
        }
        return jdbc.update(MARK_REJECTED, scope(id, customerId)
                .addValue("reasons", values.reasonsJson(RejectionReason.normalise(reasons)))
                .addValue("now", offset(now))) == 1;
    }

    @Override
    public Optional<Checkout> lockNextDueReserve(Instant now, Set<UUID> excluded) {
        return jdbc.query(NEXT_DUE, new MapSqlParameterSource()
                        .addValue("now", offset(now))
                        .addValue("excluded", uuidArray(excluded)),
                (rs, i) -> map(rs)).stream().findFirst();
    }

    @Override
    public boolean deferReserve(CheckoutId id, CustomerId customerId, Instant until, Instant now) {
        return jdbc.update(DEFER, scope(id, customerId).addValue("until", offset(until))
                .addValue("now", offset(now))) == 1;
    }

    @Override
    public Optional<Instant> oldestPendingCreatedAt() {
        return Optional.ofNullable(instant(jdbc.getJdbcTemplate()
                .queryForObject(OLDEST_PENDING, OffsetDateTime.class)));
    }

    private static MapSqlParameterSource scope(CheckoutId id, CustomerId customerId) {
        return new MapSqlParameterSource()
                .addValue("id", id.getValue())
                .addValue("customerId", customerId.getValue());
    }

    private Checkout map(ResultSet rs) throws SQLException {
        return new Checkout(
                CheckoutId.of(rs.getObject("id", UUID.class)),
                CustomerId.of(rs.getObject("customer_id", UUID.class)),
                CartId.of(rs.getObject("cart_id", UUID.class)),
                rs.getLong("cart_version"),
                CheckoutStatus.valueOf(rs.getString("status")),
                rs.getString("content_hash"),
                values.readReasons(rs.getString("reasons")),
                EventId.of(rs.getObject("causation_id", UUID.class)),
                instant(rs.getObject("requested_at", OffsetDateTime.class)),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("reserve_due_at", OffsetDateTime.class)),
                instant(rs.getObject("placed_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)));
    }
}
