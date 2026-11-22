package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import com.dotran.oms.order.application.usecase.stock.HandleStockReplyUseCase;
import lombok.RequiredArgsConstructor;

/**
 * Inventory's replies to the stock commands (ADR 0006). Not {@code @Transactional}: exactly one
 * {@link StockSagaTransactions} call per reply, which does the whole step in one transaction (poll sizing in
 * application.yaml assumes one connection wait and one 10 s transaction per record).
 */
@UseCase
@RequiredArgsConstructor
public class StockReplyService implements HandleStockReplyUseCase {

    private final StockSagaTransactions transactions;

    @Override
    public Outcome handle(StockReplyCmd cmd) {
        return transactions.apply(cmd);
    }
}
