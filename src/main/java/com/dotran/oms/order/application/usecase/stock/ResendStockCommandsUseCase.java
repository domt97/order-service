package com.dotran.oms.order.application.usecase.stock;

import com.dotran.oms.order.application.usecase.stock.ExpireDueReservesUseCase.RunResult;

/** One run of the re-send job (ADR 0006 obligation 2): open Confirm and Release commands sent again when due. */
public interface ResendStockCommandsUseCase {

    RunResult run();
}
