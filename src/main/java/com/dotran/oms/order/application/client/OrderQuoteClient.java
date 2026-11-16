package com.dotran.oms.order.application.client;

import com.dotran.oms.order.application.dto.quote.QuoteResult;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** stores-service's internal order-quote (read-only). Never throws: every failure is a {@link QuoteResult}. */
public interface OrderQuoteClient {

    /** @param timeLeft what is left of the command's quote budget; no call may last longer */
    QuoteResult quote(UUID merchantId, UUID storeId, List<String> skus, Duration timeLeft);
}
