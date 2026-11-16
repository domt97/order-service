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
 * (store- or line-level where it can be placed). A SKU that cannot be stored (NUL, over 100 characters) is reported
 * on its store only, never with its text.
 */
@Component
@RequiredArgsConstructor
public class PlaceCheckoutValidator {

    static final int MAX_STORES = 10;
    static final int MAX_LINES = 100;
    static final int MAX_SKU_LENGTH = 100;
    static final int MIN_QUANTITY = 1;
    static final int MAX_QUANTITY = 99;
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
        for (PlaceCheckoutCmd.Store store : stores) {
            if (store == null || store.storeId() == null) {
                reasons.add(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
                continue;
            }
            UUID storeId = store.storeId();
            if (!storeIds.add(storeId) || store.merchantId() == null || store.quoteId() == null) {
                reasons.add(RejectionReason.store(RejectionCode.INVALID_REQUEST, storeId));
            }
            validateLines(store, reasons);
        }
        return RejectionReason.normalise(reasons);
    }

    private static void validateLines(PlaceCheckoutCmd.Store store, List<RejectionReason> reasons) {
        UUID storeId = store.storeId();
        List<PlaceCheckoutCmd.Line> lines = store.lines();
        if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES) {
            reasons.add(RejectionReason.store(RejectionCode.INVALID_REQUEST, storeId));
            return;
        }
        Set<String> skus = new HashSet<>();
        for (PlaceCheckoutCmd.Line line : lines) {
            if (line == null || line.sku() == null || line.sku().isBlank() || !storable(line.sku())) {
                reasons.add(RejectionReason.store(RejectionCode.INVALID_REQUEST, storeId));
                continue;
            }
            boolean duplicate = !skus.add(line.sku());
            boolean badQuantity = line.quantity() == null || line.quantity() < MIN_QUANTITY
                    || line.quantity() > MAX_QUANTITY;
            if (duplicate || badQuantity || line.lineId() == null || line.productId() == null) {
                reasons.add(RejectionReason.line(RejectionCode.INVALID_REQUEST, storeId, line.sku()));
            }
        }
    }

    /** A SKU that a reason may carry: at most 100 characters and no NUL (JSONB refuses U+0000). */
    static boolean storable(String sku) {
        return sku.length() <= MAX_SKU_LENGTH && sku.indexOf('\u0000') < 0;
    }

    private static boolean validDelivery(PlaceCheckoutCmd.Delivery delivery) {
        if (delivery == null || delivery.address() == null) {
            return false;
        }
        PlaceCheckoutCmd.Address a = delivery.address();
        return notBlank(a.line1()) && notBlank(a.city()) && a.country() != null && COUNTRY.matcher(a.country()).matches()
                && notBlank(delivery.recipientName()) && delivery.recipientName().length() <= 255
                && notBlank(delivery.phone()) && delivery.phone().length() <= 32;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
