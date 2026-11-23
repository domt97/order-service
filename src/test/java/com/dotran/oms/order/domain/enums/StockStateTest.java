package com.dotran.oms.order.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.dotran.oms.order.domain.enums.StockState.*;
import static org.assertj.core.api.Assertions.assertThat;

class StockStateTest {

    @Test
    @DisplayName("allowed moves, incl. RESERVE_SENT → RELEASE_SENT (reserve timeout); every other move is refused")
    void matrix() {
        assertMoves(RESERVE_SENT, RESERVED, RESERVE_REJECTED, RELEASE_SENT);
        assertMoves(RESERVED, CONFIRM_SENT, RELEASE_SENT);
        assertMoves(CONFIRM_SENT, CONFIRMED, RELEASE_SENT);
        assertMoves(RELEASE_SENT, RELEASED);
        assertMoves(RESERVE_REJECTED);
        assertMoves(CONFIRMED);
        assertMoves(RELEASED);
    }

    @Test
    @DisplayName("only CONFIRM_SENT and RELEASE_SENT need a due time")
    void dueTime() {
        for (StockState s : values()) {
            assertThat(s.requiresDueTime()).as(s.name()).isEqualTo(s == CONFIRM_SENT || s == RELEASE_SENT);
        }
    }

    private static void assertMoves(StockState from, StockState... allowed) {
        Set<StockState> ok = allowed.length == 0 ? EnumSet.noneOf(StockState.class) : EnumSet.of(allowed[0], allowed);
        for (StockState to : values()) {
            assertThat(from.canMoveTo(to)).as(from + " → " + to).isEqualTo(ok.contains(to));
        }
    }
}
