package com.dotran.oms.order.domain.valueobject;

import com.dotran.oms.order.domain.enums.RejectionCode;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One reason of a rejected checkout: checkout-level (no store), store-level or line-level (store and SKU). The SKU is
 * only ever a code that can be stored (never one with NUL: JSONB refuses it).
 */
public record RejectionReason(RejectionCode code, UUID storeId, String sku) {

    private static final Comparator<RejectionReason> ORDER = Comparator
            .comparing(RejectionReason::storeId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(RejectionReason::sku, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(RejectionReason::code);

    public RejectionReason {
        Objects.requireNonNull(code, "code");
    }

    public static RejectionReason checkout(RejectionCode code) {
        return new RejectionReason(code, null, null);
    }

    public static RejectionReason store(RejectionCode code, UUID storeId) {
        return new RejectionReason(code, storeId, null);
    }

    public static RejectionReason line(RejectionCode code, UUID storeId, String sku) {
        return new RejectionReason(code, storeId, sku);
    }

    /** Distinct reasons in a fixed order (store, SKU, code), so the payload is the same on every run and copy. */
    public static List<RejectionReason> normalise(Collection<RejectionReason> reasons) {
        return reasons.stream().distinct().sorted(ORDER).toList();
    }
}
