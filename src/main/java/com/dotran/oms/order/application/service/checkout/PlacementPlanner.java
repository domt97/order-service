package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.dto.quote.OrderQuote;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import com.dotran.oms.order.domain.valueobject.CurrencyRules;
import com.dotran.oms.order.domain.valueobject.DeliverySnapshot;
import com.dotran.oms.order.domain.valueobject.PlacementPlan;
import com.dotran.oms.order.domain.valueobject.StoreOrder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the orders of a checkout that passed every check: one per store (BR-ORD-01), each line snapshotted from the
 * store's quote (BR-ORD-03), totals rounded to the currency (BR-ORD-04), the delivery data copied (BR-ORD-15) and the
 * store's local day kept (D8). Only called after {@link QuoteReasonMapper} found no reason, so every SKU is in its
 * quote with a price and a name, the currency has minor units and the local day is known.
 */
@Component
public class PlacementPlanner {

    public PlacementPlan plan(Checkout checkout, PlaceCheckoutCmd cmd, Map<UUID, OrderQuote> quotes, Instant now) {
        DeliverySnapshot delivery = delivery(cmd.delivery());
        return new PlacementPlan(checkout, cmd.stores().stream()
                .map(store -> storeOrder(checkout, cmd, store, quotes.get(store.storeId()), delivery, now))
                .toList());
    }

    private static StoreOrder storeOrder(Checkout checkout, PlaceCheckoutCmd cmd, PlaceCheckoutCmd.Store store,
                                         OrderQuote quote, DeliverySnapshot delivery, Instant now) {
        if (quote == null) {
            throw new IllegalStateException("No quote for storeId=" + store.storeId());
        }
        int minorUnits = CurrencyRules.minorUnits(quote.storeCurrency()).orElseThrow(() ->
                new IllegalStateException("Unusable currency for storeId=" + store.storeId()));
        Map<String, OrderQuote.SkuQuote> bySku = quote.skus().stream()
                .filter(s -> s.sku() != null)
                .collect(Collectors.toMap(OrderQuote.SkuQuote::sku, Function.identity(), (a, b) -> a));
        var lines = store.lines().stream().map(line -> {
            OrderQuote.SkuQuote sku = bySku.get(line.sku());
            return OrderLine.snapshot(line.lineId(), ProductId.of(line.productId()), line.sku(), sku.productName(),
                    sku.skuName(), line.quantity(), sku.price(), quote.storeCurrency(), minorUnits);
        }).toList();
        Order order = Order.pending(checkout.id(), cmd.customerId(), MerchantId.of(store.merchantId()),
                StoreId.of(store.storeId()), quote.storeCode(), quote.localDate(), quote.storeCurrency(),
                store.quoteId(), delivery, lines, now);
        return new StoreOrder(order, quote.maxOrdersPerDay());
    }

    private static DeliverySnapshot delivery(PlaceCheckoutCmd.Delivery d) {
        PlaceCheckoutCmd.Address a = d.address();
        return new DeliverySnapshot(d.recipientName(), d.phone(), a.line1(), a.line2(), a.ward(), a.district(),
                a.city(), a.postalCode(), a.country());
    }
}
