package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.application.repository.StoreDayCounterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

/**
 * The stores' daily counters (ADR 0007 Counters) with plain JDBC, one row per statement. Every statement is scoped by
 * the merchant: a row of another merchant is never changed (system rule 4).
 */
@PersistenceAdapter
@RequiredArgsConstructor
public class StoreDayCounterPersistenceAdapter implements StoreDayCounterRepository {

    // The row lock taken here is held to the end of the placement transaction, which serialises the cap per store.
    private static final String TAKE = """
            INSERT INTO store_day_counters (store_id, local_date, merchant_id, placed_count, last_seq)
            VALUES (:storeId, :localDate, :merchantId, 1, 0)
            ON CONFLICT (store_id, local_date) DO UPDATE
                SET placed_count = store_day_counters.placed_count + 1
                WHERE store_day_counters.merchant_id = :merchantId
                  AND (CAST(:max AS int) IS NULL OR store_day_counters.placed_count < CAST(:max AS int))
            RETURNING placed_count
            """;
    private static final String OWNED = """
            SELECT count(*) FROM store_day_counters
            WHERE store_id = :storeId AND local_date = :localDate AND merchant_id = :merchantId
            """;
    private static final String NEXT_SEQ = """
            UPDATE store_day_counters SET last_seq = last_seq + 1
            WHERE store_id = :storeId AND local_date = :localDate AND merchant_id = :merchantId
            RETURNING last_seq
            """;
    private static final String GIVE_BACK = """
            UPDATE store_day_counters SET placed_count = placed_count - 1
            WHERE store_id = :storeId AND local_date = :localDate AND merchant_id = :merchantId AND placed_count > 0
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public SlotResult takeSlot(StoreId storeId, LocalDate localDate, MerchantId merchantId, Integer maxOrdersPerDay) {
        if (maxOrdersPerDay != null && maxOrdersPerDay <= 0) {
            return SlotResult.CAPPED; // D6: a cap of 0 or less admits nothing
        }
        List<Integer> taken = jdbc.queryForList(TAKE, params(storeId, localDate, merchantId)
                .addValue("max", maxOrdersPerDay), Integer.class);
        if (!taken.isEmpty()) {
            return SlotResult.TAKEN;
        }
        // 0 rows: the row exists and was not updated. Capped if it is this merchant's; otherwise it is another
        // merchant's row for this store, which must never happen (a store belongs to one merchant).
        Integer owned = jdbc.queryForObject(OWNED, params(storeId, localDate, merchantId), Integer.class);
        if (owned != null && owned == 1) {
            return SlotResult.CAPPED;
        }
        throw new IllegalStateException("store_day_counters row of store " + storeId.getValue()
                + " belongs to another merchant than " + merchantId.getValue());
    }

    @Override
    public OptionalInt nextSeq(StoreId storeId, LocalDate localDate, MerchantId merchantId) {
        List<Integer> seq = jdbc.queryForList(NEXT_SEQ, params(storeId, localDate, merchantId), Integer.class);
        return seq.isEmpty() ? OptionalInt.empty() : OptionalInt.of(seq.get(0));
    }

    @Override
    public boolean giveBack(StoreId storeId, LocalDate localDate, MerchantId merchantId) {
        return jdbc.update(GIVE_BACK, params(storeId, localDate, merchantId)) == 1;
    }

    private static MapSqlParameterSource params(StoreId storeId, LocalDate localDate, MerchantId merchantId) {
        return new MapSqlParameterSource()
                .addValue("storeId", storeId.getValue())
                .addValue("localDate", Date.valueOf(localDate))
                .addValue("merchantId", merchantId.getValue());
    }
}
