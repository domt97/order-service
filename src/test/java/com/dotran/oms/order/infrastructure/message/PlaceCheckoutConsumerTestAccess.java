package com.dotran.oms.order.infrastructure.message;

/** The valid PlaceCheckout sample of {@link PlaceCheckoutConsumerTest}, for the contract test. */
public final class PlaceCheckoutConsumerTestAccess {

    private PlaceCheckoutConsumerTestAccess() {
    }

    public static String sample() {
        return PlaceCheckoutConsumerTest.MESSAGE;
    }
}
