package com.dotran.oms.order.application.event;

import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builds the CheckoutRejected outbox rows: always a new eventId; causationId = the command it answers. */
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
}
