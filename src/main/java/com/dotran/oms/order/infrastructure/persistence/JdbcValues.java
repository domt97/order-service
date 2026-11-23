package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Conversions shared by the JDBC adapters: reasons as JSONB, instants as timestamptz, UUID sets as a uuid[] literal. */
@Component
@RequiredArgsConstructor
class JdbcValues {

    private static final TypeReference<List<StoredReason>> REASONS = new TypeReference<>() {
    };

    private final OutboxPayloadSerializer serializer;

    String reasonsJson(List<RejectionReason> reasons) {
        return reasons == null ? null : serializer.toJson(reasons.stream()
                .map(r -> new StoredReason(r.code().name(), r.storeId(), r.sku())).toList());
    }

    List<RejectionReason> readReasons(String json) {
        if (json == null) {
            return null;
        }
        try {
            return serializer.mapper().readValue(json, REASONS).stream()
                    .map(r -> new RejectionReason(RejectionCode.valueOf(r.code()), r.storeId(), r.sku()))
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable stored reasons", e);
        }
    }

    /**
     * A {@code uuid[]} literal for {@code CAST(:x AS uuid[])}. A Java collection would be expanded by
     * NamedParameterJdbcTemplate into {@code ?, ?}, which is invalid inside {@code ANY(...)}; an empty set is '{}'.
     */
    static String uuidArray(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoredReason(String code, UUID storeId, String sku) {
    }
}
