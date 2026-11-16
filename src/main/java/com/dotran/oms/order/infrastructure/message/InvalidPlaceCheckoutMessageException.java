package com.dotran.oms.order.infrastructure.message;

/**
 * A PlaceCheckout record that cannot be answered: wrong eventType, or a missing/invalid eventId, customerId, cartId or
 * cartVersion (no key to store an outcome under). Not retryable: straight to PlaceCheckout.DLT. The message names the
 * field only, never its value.
 */
public class InvalidPlaceCheckoutMessageException extends RuntimeException {

    public InvalidPlaceCheckoutMessageException(String message) {
        super(message);
    }
}
