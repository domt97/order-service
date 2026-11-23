package com.dotran.oms.order.domain.model;

import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of an order: a snapshot of the SKU, its names and its unit price at placement (BR-ORD-03). The id is the
 * {@code orderItemId} sent to inventory; the cart line id is kept to map reasons back (BR-CRT-04).
 */
public record OrderLine(OrderItemId id, UUID cartLineId, ProductId productId, String sku, String productName,
                        String skuName, int quantity, BigDecimal unitPrice, String currency, BigDecimal lineTotal) {

    public OrderLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cartLineId, "cartLineId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(lineTotal, "lineTotal");
        if (quantity < 1 || quantity > 99) {
            throw new IllegalArgumentException("quantity must be 1..99");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
    }

    /** A new line: {@code lineTotal = unitPrice × quantity}, rounded HALF_UP to the currency's minor units (BR-ORD-04). */
    public static OrderLine snapshot(UUID cartLineId, ProductId productId, String sku, String productName,
                                     String skuName, int quantity, BigDecimal unitPrice, String currency,
                                     int minorUnits) {
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(minorUnits, RoundingMode.HALF_UP);
        return new OrderLine(OrderItemId.newOrderItemId(), cartLineId, productId, sku, productName, skuName, quantity,
                unitPrice, currency, total);
    }
}
