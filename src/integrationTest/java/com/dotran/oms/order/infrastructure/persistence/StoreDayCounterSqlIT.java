package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.application.repository.StoreDayCounterRepository;
import com.dotran.oms.order.application.repository.StoreDayCounterRepository.SlotResult;
import com.dotran.oms.order.support.Orders;
import com.dotran.oms.order.support.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreDayCounterSqlIT extends PostgresIT {

    @Autowired
    private StoreDayCounterRepository counters;

    private final StoreId store = StoreId.of(UUID.randomUUID());
    private final MerchantId merchant = MerchantId.of(UUID.randomUUID());

    private SlotResult take(Integer max) {
        return inTx(() -> counters.takeSlot(store, Orders.DAY, merchant, max));
    }

    @Test
    @DisplayName("cap: max 2 admits two, then CAPPED; null is unlimited; max 0 or less admits nothing (D6)")
    void cap() {
        assertThat(take(2)).isEqualTo(SlotResult.TAKEN);
        assertThat(take(2)).isEqualTo(SlotResult.TAKEN);
        assertThat(take(2)).isEqualTo(SlotResult.CAPPED);
        assertThat(take(null)).isEqualTo(SlotResult.TAKEN);
        assertThat(count("SELECT placed_count FROM store_day_counters WHERE store_id = ?", store.getValue()))
                .isEqualTo(3);
        StoreId other = StoreId.of(UUID.randomUUID());
        assertThat(inTx(() -> counters.takeSlot(other, Orders.DAY, merchant, 0))).isEqualTo(SlotResult.CAPPED);
        assertThat(inTx(() -> counters.takeSlot(other, Orders.DAY, merchant, -1))).isEqualTo(SlotResult.CAPPED);
        assertThat(count("SELECT count(*) FROM store_day_counters WHERE store_id = ?", other.getValue())).isZero();
    }

    @Test
    @DisplayName("two placements race for the last slot (max 1): exactly one TAKEN")
    void race() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<SlotResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return take(1);
                }));
            }
            start.countDown();
            List<SlotResult> got = new ArrayList<>();
            for (Future<SlotResult> f : results) {
                got.add(f.get(20, TimeUnit.SECONDS));
            }
            assertThat(got).containsExactlyInAnyOrder(SlotResult.TAKEN, SlotResult.CAPPED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a row of another merchant: takeSlot throws, nextSeq and giveBack change nothing")
    void merchantScope() {
        take(null);
        MerchantId other = MerchantId.of(UUID.randomUUID());
        assertThatThrownBy(() -> inTx(() -> counters.takeSlot(store, Orders.DAY, other, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(inTx(() -> counters.nextSeq(store, Orders.DAY, other))).isEmpty();
        assertThat(inTx(() -> counters.giveBack(store, Orders.DAY, other))).isFalse();
        assertThat(count("SELECT placed_count FROM store_day_counters WHERE store_id = ?", store.getValue()))
                .isEqualTo(1);
        assertThat(count("SELECT last_seq FROM store_day_counters WHERE store_id = ?", store.getValue())).isZero();
    }

    @Test
    @DisplayName("nextSeq counts 1, 2, …; empty without a row; giveBack floors at 0")
    void seqAndGiveBack() {
        assertThat(inTx(() -> counters.nextSeq(store, Orders.DAY, merchant))).isEmpty();
        take(null);
        assertThat(inTx(() -> counters.nextSeq(store, Orders.DAY, merchant))).hasValue(1);
        assertThat(inTx(() -> counters.nextSeq(store, Orders.DAY, merchant))).hasValue(2);
        assertThat(inTx(() -> counters.giveBack(store, Orders.DAY, merchant))).isTrue();
        assertThat(inTx(() -> counters.giveBack(store, Orders.DAY, merchant))).isFalse();
        assertThat(count("SELECT placed_count FROM store_day_counters WHERE store_id = ?", store.getValue()))
                .isZero();
    }
}
