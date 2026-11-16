package com.dotran.oms.order.application.dto.quote;

/** The answer of one order-quote call (integration.md, *Answers*). */
public sealed interface QuoteResult {

    /** 200 with a readable body. */
    record Ok(OrderQuote quote) implements QuoteResult {
    }

    /** 404: the merchant does not exist or the store is not the merchant's. Final (INVALID_REQUEST, alert). */
    record NotFound() implements QuoteResult {
    }

    /** 400: the request was wrong (a bug on our side). INVALID_REQUEST. */
    record BadRequest() implements QuoteResult {
    }

    /** 5xx, another 4xx, a timeout, a connection failure, an unreadable body, or no budget left. STORE_UNAVAILABLE. */
    record Unavailable(String why) implements QuoteResult {
    }
}
