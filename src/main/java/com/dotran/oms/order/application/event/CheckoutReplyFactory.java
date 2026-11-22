package com.dotran.oms.order.application.event;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds the CheckoutRejected and CheckoutPlaced outbox rows: always a new eventId; causationId = the PlaceCheckout it
 * answers; key = cartId; customer-scoped (V1 CHECK).
 */
@Component
public class CheckoutReplyFactory {

    public OutboxMessage rejected(PlaceCheckoutCmd cmd, CheckoutId checkoutId, List<RejectionReason> reasons,
                                  Instant now) {
        UUID eventId = UUID.randomUUID();
        CheckoutRejectedEvent payload = new CheckoutRejectedEvent(eventId, CheckoutRejectedEvent.TYPE, now,
                cmd.eventId().getValue(), cmd.customerId().getValue(), cmd.cartId().getValue(), cmd.cartVersion(),
                checkoutId == null ? null : checkoutId.getValue(),
                RejectionReason.normalise(reasons).stream()
                        .map(r -> new CheckoutRejectedEvent.Reason(r.storeId(), r.sku(), r.code().name()))
                        .toList());
        return new OutboxMessage(eventId, cmd.customerId().getValue(), null, CheckoutRejectedEvent.AGGREGATE_TYPE,
                cmd.cartId().getValue(), CheckoutRejectedEvent.TYPE, payload);
    }

    /** Same key, other content or another customer's key: no checkout row for this content, so no checkoutId. */
    public OutboxMessage contentMismatch(PlaceCheckoutCmd cmd, Instant now) {
        return rejected(cmd, null, List.of(RejectionReason.checkout(RejectionCode.CONTENT_MISMATCH)), now);
    }

    /**
     * CheckoutPlaced for a PLACED checkout: every order with its number. {@code causationId} is the PlaceCheckout it
     * answers (the stored first one at placement, the new copy when sent again).
     */
    public OutboxMessage placed(Checkout checkout, List<Order> orders, EventId causationId, Instant now) {
        if (orders.isEmpty() || orders.stream().anyMatch(o -> o.orderNumber() == null)) {
            throw new IllegalStateException("CheckoutPlaced needs every order numbered: checkoutId="
                    + checkout.id().getValue());
        }
        UUID eventId = UUID.randomUUID();
        CheckoutPlacedEvent payload = new CheckoutPlacedEvent(eventId, CheckoutPlacedEvent.TYPE, now,
                causationId.getValue(), checkout.customerId().getValue(), checkout.cartId().getValue(),
                checkout.cartVersion(), checkout.id().getValue(),
                orders.stream().map(o -> new CheckoutPlacedEvent.PlacedOrder(o.id().getValue(),
                        o.merchantId().getValue(), o.storeId().getValue(), o.orderNumber())).toList());
        return new OutboxMessage(eventId, checkout.customerId().getValue(), null, CheckoutRejectedEvent.AGGREGATE_TYPE,
                checkout.cartId().getValue(), CheckoutPlacedEvent.TYPE, payload);
    }

    /**
     * CheckoutRejected for a checkout rejected after placement started (inventory's replies or the reserve timeout):
     * built from the stored row, causationId = the PlaceCheckout stored with it.
     */
    public OutboxMessage rejected(Checkout checkout, List<RejectionReason> reasons, Instant now) {
        UUID eventId = UUID.randomUUID();
        CheckoutRejectedEvent payload = new CheckoutRejectedEvent(eventId, CheckoutRejectedEvent.TYPE, now,
                checkout.causationId().getValue(), checkout.customerId().getValue(), checkout.cartId().getValue(),
                checkout.cartVersion(), checkout.id().getValue(),
                RejectionReason.normalise(reasons).stream()
                        .map(r -> new CheckoutRejectedEvent.Reason(r.storeId(), r.sku(), r.code().name()))
                        .toList());
        return new OutboxMessage(eventId, checkout.customerId().getValue(), null, CheckoutRejectedEvent.AGGREGATE_TYPE,
                checkout.cartId().getValue(), CheckoutRejectedEvent.TYPE, payload);
    }
}
