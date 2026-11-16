package com.dotran.oms.order.support;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.common.id.CartId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test data: a valid PlaceCheckout command with one store and one line. */
public final class Checkouts {

    public static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    public static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    public static final UUID CART = UUID.fromString("00000000-0000-0000-0000-0000000ca001");
    public static final UUID MERCHANT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    public static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    public static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-00000000d001");

    private Checkouts() {
    }

    public static PlaceCheckoutCmd.Line line(String sku) {
        return new PlaceCheckoutCmd.Line(UUID.randomUUID(), PRODUCT, sku, 1);
    }

    public static PlaceCheckoutCmd.Store store(UUID storeId, PlaceCheckoutCmd.Line... lines) {
        return new PlaceCheckoutCmd.Store(MERCHANT, storeId, UUID.fromString("00000000-0000-0000-0000-0000000e0001"),
                List.of(lines));
    }

    public static PlaceCheckoutCmd.Delivery delivery() {
        return new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address("1 Le Loi", null, "Ben Nghe", "1",
                "Ho Chi Minh", "VN", null), "Nguyen Van A", "0901234567");
    }

    public static PlaceCheckoutCmd.PlaceCheckoutCmdBuilder valid() {
        return PlaceCheckoutCmd.builder()
                .eventId(EventId.of(UUID.randomUUID()))
                .occurredAt(NOW)
                .customerId(CustomerId.of(CUSTOMER))
                .cartId(CartId.of(CART))
                .cartVersion(3)
                .requestedAt(NOW.minusSeconds(5))
                .delivery(delivery())
                .stores(List.of(store(STORE, line("TS-M"))));
    }
}
