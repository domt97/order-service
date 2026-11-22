package com.dotran.oms.order.infrastructure.job;

import com.dotran.oms.core.annotation.JobAdapter;
import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.order.application.usecase.stock.ExpireDueReservesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

/**
 * Every 10 s after the previous run ended (fixedDelay: runs never overlap on one instance; several instances split the
 * due rows through SKIP LOCKED). The startup grace is in the service.
 */
@JobAdapter
@RequiredArgsConstructor
@Slf4j
public class ReserveTimeoutJob {

    private final ExpireDueReservesUseCase useCase;

    @Scheduled(fixedDelayString = "${app.stock.job-delay:PT10S}", initialDelayString = "${app.stock.job-delay:PT10S}")
    public void run() {
        try (MdcScope mdc = MdcScope.open()) {
            mdc.put(MdcKeys.REQUEST_ID, UUID.randomUUID());
            try {
                useCase.run();
            } catch (RuntimeException e) {
                // The service catches its own failures; this keeps the scheduler thread alive if one slips through.
                log.error("Reserve-timeout run failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
