package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.config.CheckoutProperties;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The rules of the PlaceCheckout contract that a readable command can break; each break is an INVALID_REQUEST reason
 * (store- or line-level where it can be placed). Lengths are counted in Unicode code points, as JSON Schema
 * {@code maxLength} does. A SKU that cannot be stored (NUL, an unpaired surrogate, blank, over 100 code points) is
 * reported on its store only, never with its text. A bad delivery field is one checkout-level reason without any text
 * (personal data, BR-ORD-15). A lineId appears once in the whole command (BR-ORD-14).
 */
@Component
@RequiredArgsConstructor
public class PlaceCheckoutValidator {

    static final int MAX_STORES = 10;
    static final int MAX_LINES = 100;
    static final int MAX_SKU_LENGTH = 100;
    static final int MIN_QUANTITY = 1;
    static final int MAX_QUANTITY = 99;
    static final int MAX_LINE1 = 255;
    static final int MAX_LINE2 = 255;
    static final int MAX_WARD = 100;
    static final int MAX_DISTRICT = 100;
    static final int MAX_CITY = 100;
    static final int MAX_POSTAL_CODE = 20;
    static final int MAX_RECIPIENT_NAME = 255;
    static final int MAX_PHONE = 32;
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    private final CheckoutProperties properties;

    public List<RejectionReason> validate(PlaceCheckoutCmd cmd, Instant now) {
        List<RejectionReason> reasons = new ArrayList<>();
        if (cmd.requestedAt() == null || cmd.requestedAt().isAfter(now.plus(properties.clockSkew()))) {
            reasons.add(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        }
        if (!validDelivery(cmd.delivery())) {
            reasons.add(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        }
        List<PlaceCheckoutCmd.Store> stores = cmd.stores();
        if (stores == null || stores.isEmpty() || stores.size() > MAX_STORES) {
            reasons.add(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
            return RejectionReason.normalise(reasons);
        }
        Set<UUID> storeIds = new HashSet<>();
        Set<UUID> lineIds = new HashSet<>();
        for (PlaceCheckoutCmd.Store store : stores) {
            if (store == null || store.storeId() == null) {
                reasons.add(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
                continue;
            }
            UUID storeId = store.storeId();
            if (!storeIds.add(storeId) || store.merchantId() == null || store.quoteId() == null) {
                reasons.add(RejectionReason.store(RejectionCode.INVALID_REQUEST, storeId));
            }
            validateLines(store, lineIds, reasons);
        }
        return RejectionReason.normalise(reasons);
    }

    private static void validateLines(PlaceCheckoutCmd.Store store, Set<UUID> lineIds, List<RejectionReason> reasons) {
        UUID storeId = store.storeId();
        List<PlaceCheckoutCmd.Line> lines = store.lines();
        if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES) {
            reasons.add(RejectionReason.store(RejectionCode.INVALID_REQUEST, storeId));
            return;
        }
        Set<String> skus = new HashSet<>();
        for (PlaceCheckoutCmd.Line line : lines) {
            if (line == null) {
                reasons.add(RejectionReason.store(RejectionCode.INVALID_REQUEST, storeId));
                continue;
            }
            // Claimed before the SKU check, so a line with a bad SKU still takes its lineId.
            boolean duplicateLineId = line.lineId() != null && !lineIds.add(line.lineId());
            if (!storable(line.sku())) {
                reasons.add(RejectionReason.store(RejectionCode.INVALID_REQUEST, storeId));
                continue;
            }
            boolean duplicateSku = !skus.add(line.sku());
            boolean badQuantity = line.quantity() == null || line.quantity() < MIN_QUANTITY
                    || line.quantity() > MAX_QUANTITY;
            if (duplicateLineId || duplicateSku || badQuantity || line.lineId() == null || line.productId() == null) {
                reasons.add(RejectionReason.line(RejectionCode.INVALID_REQUEST, storeId, line.sku()));
            }
        }
    }

    /**
     * A SKU that a reason may carry: 1–100 code points, not blank, no NUL (Postgres text and JSONB refuse U+0000) and
     * no unpaired surrogate.
     */
    static boolean storable(String sku) {
        return text(sku, MAX_SKU_LENGTH, true);
    }

    private static boolean validDelivery(PlaceCheckoutCmd.Delivery delivery) {
        if (delivery == null || delivery.address() == null) {
            return false;
        }
        PlaceCheckoutCmd.Address a = delivery.address();
        return text(a.line1(), MAX_LINE1, true)
                && text(a.line2(), MAX_LINE2, false)
                && text(a.ward(), MAX_WARD, false)
                && text(a.district(), MAX_DISTRICT, false)
                && text(a.city(), MAX_CITY, true)
                && a.country() != null && COUNTRY.matcher(a.country()).matches()
                && text(a.postalCode(), MAX_POSTAL_CODE, false)
                && text(delivery.recipientName(), MAX_RECIPIENT_NAME, true)
                && text(delivery.phone(), MAX_PHONE, true);
    }

    /**
     * A text field of the contract: absent only when optional (an optional field may also be empty); when required,
     * not blank ({@link String#isBlank()}, i.e. {@link Character#isWhitespace}); at most {@code max} code points; no NUL
     * and no unpaired surrogate ({@code codePoints()} yields a lone or reversed half as its own surrogate value).
     */
    static boolean text(String value, int max, boolean required) {
        if (value == null) {
            return !required;
        }
        if (required && value.isBlank()) {
            return false;
        }
        if (value.codePointCount(0, value.length()) > max) {
            return false;
        }
        return value.codePoints().noneMatch(c -> c == 0 || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE));
    }
}
