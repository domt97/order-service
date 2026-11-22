package com.dotran.oms.order.application.usecase.stock;

import com.dotran.oms.order.application.command.stock.StockReplyCmd;

public interface HandleStockReplyUseCase {

    /**
     * Idempotent: each reply type moves the order out of exactly one state, so a repeated, late or re-published reply
     * finds it elsewhere and changes nothing (NO_CHANGE).
     */
    Outcome handle(StockReplyCmd cmd);

    enum Outcome {
        /** The order moved (and the checkout may have been decided). */
        APPLIED,
        /** The order is no longer in the state this reply answers: nothing written. */
        NO_CHANGE
    }
}
