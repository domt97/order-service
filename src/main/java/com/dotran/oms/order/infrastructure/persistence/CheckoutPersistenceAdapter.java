package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Checkouts with plain JDBC: the insert is {@code ON CONFLICT (cart_id, cart_version) DO NOTHING} (the key decides every
 * race, ADR 0007) and the reasons are JSONB. Every read filters by the customer (system rule 4).
 */
@PersistenceAdapter
@RequiredArgsConstructor
public class CheckoutPersistenceAdapter implements CheckoutRepository {

    private static final String INSERT = """
            INSERT INTO checkouts (id, customer_id, cart_id, cart_version, status, content_hash, reasons, causation_id,
                                   requested_at, created_at)
            VALUES (:id, :customerId, :cartId, :cartVersion, :status, :contentHash, CAST(:reasons AS jsonb),
                    :causationId, :requestedAt, :createdAt)
            ON CONFLICT (cart_id, cart_version) DO NOTHING
            """;
    private static final String FIND = """
            SELECT id, customer_id, cart_id, cart_version, status, content_hash, reasons, causation_id, requested_at,
                   created_at
            FROM checkouts
            WHERE cart_id = :cartId AND cart_version = :cartVersion AND customer_id = :customerId
            """;
    private static final TypeReference<List<StoredReason>> REASONS = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final OutboxPayloadSerializer serializer;

    @Override
    public boolean insertIfAbsent(Checkout c) {
        int rows = jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("id", c.id().getValue())
                .addValue("customerId", c.customerId().getValue())
                .addValue("cartId", c.cartId().getValue())
                .addValue("cartVersion", c.cartVersion())
                .addValue("status", c.status().name())
                .addValue("contentHash", c.contentHash())
                .addValue("reasons", c.reasons() == null ? null : serializer.toJson(c.reasons().stream()
                        .map(r -> new StoredReason(r.code().name(), r.storeId(), r.sku())).toList()))
                .addValue("causationId", c.causationId().getValue())
                .addValue("requestedAt", offset(c.requestedAt()))
                .addValue("createdAt", offset(c.createdAt())));
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

    private Checkout map(ResultSet rs) throws SQLException {
        String reasons = rs.getString("reasons");
        return new Checkout(
                CheckoutId.of(rs.getObject("id", UUID.class)),
                CustomerId.of(rs.getObject("customer_id", UUID.class)),
                CartId.of(rs.getObject("cart_id", UUID.class)),
                rs.getLong("cart_version"),
                CheckoutStatus.valueOf(rs.getString("status")),
                rs.getString("content_hash"),
                reasons == null ? null : readReasons(reasons),
                EventId.of(rs.getObject("causation_id", UUID.class)),
                instant(rs.getObject("requested_at", OffsetDateTime.class)),
                instant(rs.getObject("created_at", OffsetDateTime.class)));
    }

    private List<RejectionReason> readReasons(String json) {
        try {
            return serializer.mapper().readValue(json, REASONS).stream()
                    .map(r -> new RejectionReason(RejectionCode.valueOf(r.code()), r.storeId(), r.sku()))
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable checkout reasons", e);
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoredReason(String code, UUID storeId, String sku) {
    }
}
