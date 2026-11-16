package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * SHA-256 of what a checkout is (BR-ORD-05: same key + other content is rejected): customer, delivery, stores with
 * their quote and lines. Not part of it: eventId, occurredAt, requestedAt (they differ between copies). Stores are
 * sorted by storeId and lines by (sku, lineId), every field is length-prefixed (no two contents give one input).
 * The input holds personal data (the address); only the hash is stored.
 */
final class ContentHash {

    private static final Comparator<PlaceCheckoutCmd.Store> STORE_ORDER = Comparator.comparing(
            s -> String.valueOf(s == null ? null : s.storeId()));
    private static final Comparator<PlaceCheckoutCmd.Line> LINE_ORDER = Comparator
            .comparing((PlaceCheckoutCmd.Line l) -> String.valueOf(l == null ? null : l.sku()))
            .thenComparing(l -> String.valueOf(l == null ? null : l.lineId()));

    private ContentHash() {
    }

    static String of(PlaceCheckoutCmd cmd) {
        StringBuilder in = new StringBuilder();
        field(in, cmd.customerId().getValue());
        PlaceCheckoutCmd.Delivery d = cmd.delivery();
        field(in, d == null ? null : "delivery");
        if (d != null) {
            field(in, d.recipientName());
            field(in, d.phone());
            PlaceCheckoutCmd.Address a = d.address();
            field(in, a == null ? null : "address");
            if (a != null) {
                field(in, a.line1());
                field(in, a.line2());
                field(in, a.ward());
                field(in, a.district());
                field(in, a.city());
                field(in, a.country());
                field(in, a.postalCode());
            }
        }
        List<PlaceCheckoutCmd.Store> stores = cmd.stores() == null ? null
                : cmd.stores().stream().sorted(STORE_ORDER).toList();
        field(in, stores == null ? null : stores.size());
        if (stores != null) {
            for (PlaceCheckoutCmd.Store s : stores) {
                field(in, s == null ? null : "store");
                if (s == null) {
                    continue;
                }
                field(in, s.merchantId());
                field(in, s.storeId());
                field(in, s.quoteId());
                List<PlaceCheckoutCmd.Line> lines = s.lines() == null ? null
                        : s.lines().stream().sorted(LINE_ORDER).toList();
                field(in, lines == null ? null : lines.size());
                if (lines != null) {
                    for (PlaceCheckoutCmd.Line l : lines) {
                        field(in, l == null ? null : "line");
                        if (l != null) {
                            field(in, l.lineId());
                            field(in, l.productId());
                            field(in, l.sku());
                            field(in, l.quantity());
                        }
                    }
                }
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void field(StringBuilder in, Object value) {
        if (value == null) {
            in.append("-1:");
            return;
        }
        String text = Objects.toString(value);
        in.append(text.length()).append(':').append(text);
    }
}
