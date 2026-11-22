package com.dotran.oms.order.application.service.stock;

/**
 * A stock reply that cannot belong to the order it names: unknown order for this merchant and store, a missing field,
 * or lines that do not match the order. Not retryable: straight to the topic's DLT, nothing written. The message
 * names the field or rule only, never a value.
 */
public class InvalidStockReplyException extends RuntimeException {

    public InvalidStockReplyException(String message) {
        super(message);
    }
}
