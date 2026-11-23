package com.dotran.oms.order.application.repository;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.StoreId;

import java.time.LocalDate;
import java.util.OptionalInt;

/**
 * A store's counters for one local day (BR-STO-07 cap, BR-ORD-10 sequence). Every statement is scoped by the merchant;
 * callers change counters one row per statement, in store id order (no lock cycle). Inside the caller's transaction.
 */
public interface StoreDayCounterRepository {

    /**
     * Takes one daily slot: TAKEN, or CAPPED when {@code maxOrdersPerDay} is reached (≤ 0 counts as 0; null is
     * unlimited). Throws IllegalStateException when the row belongs to another merchant.
     */
    SlotResult takeSlot(StoreId storeId, LocalDate localDate, MerchantId merchantId, Integer maxOrdersPerDay);

    /** The next order sequence of the day; empty when the row does not exist for this merchant. */
    OptionalInt nextSeq(StoreId storeId, LocalDate localDate, MerchantId merchantId);

    /** Gives one slot back; false when nothing was taken (count already 0) or the row is not this merchant's. */
    boolean giveBack(StoreId storeId, LocalDate localDate, MerchantId merchantId);

    enum SlotResult {
        TAKEN, CAPPED
    }
}
