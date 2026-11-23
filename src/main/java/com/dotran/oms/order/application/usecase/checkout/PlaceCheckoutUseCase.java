package com.dotran.oms.order.application.usecase.checkout;

import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;

public interface PlaceCheckoutUseCase {

    /** Idempotent by cart id + version (BR-ORD-05): any copy may be delivered any number of times. */
    PlaceCheckoutOutcome place(PlaceCheckoutCmd cmd);

    /** What happened, for the log line and tests; the reply (if any) is already in the outbox. */
    enum PlaceCheckoutOutcome {
        /** A new REJECTED or STALE row and its CheckoutRejected. */
        REJECTED_STORED,
        /** The key's stored outcome (CheckoutRejected or CheckoutPlaced) was sent again. */
        REPUBLISHED,
        /** Same key, other content (or another customer's key): CheckoutRejected{CONTENT_MISMATCH}, no row. */
        CONTENT_MISMATCH,
        /** Every check passed: the PENDING checkout, its orders and their ReserveStock are written. */
        PLACEMENT_STARTED,
        /** A PENDING row exists: inventory's replies decide it; nothing sent now. */
        IN_PROGRESS
    }
}
