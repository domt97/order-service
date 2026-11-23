package com.dotran.oms.order.domain.model;

import com.dotran.oms.core.domain.id.ProductId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderLineTest {

    private static OrderLine line(String price, int qty, String currency, int minor) {
        return OrderLine.snapshot(UUID.randomUUID(), ProductId.of(UUID.randomUUID()), "TS-M", "Tee", "M", qty,
                new BigDecimal(price), currency, minor);
    }

    @Test
    @DisplayName("line total = price × qty, HALF_UP to the currency's minor units (BR-ORD-04)")
    void rounding() {
        assertThat(line("12500.5", 3, "VND", 0).lineTotal()).isEqualByComparingTo("37502");
        assertThat(line("0.125", 1, "USD", 2).lineTotal()).isEqualByComparingTo("0.13");
        assertThat(line("0.1235", 1, "KWD", 3).lineTotal()).isEqualByComparingTo("0.124");
        assertThat(line("1.00005", 2, "USD", 2).lineTotal()).isEqualByComparingTo("2.00");
        assertThat(line("1.00005", 2, "USD", 2).lineTotal().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("quantity 1..99 and a negative price are refused")
    void invariants() {
        assertThatThrownBy(() -> line("1", 0, "VND", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line("1", 100, "VND", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line("-1", 1, "VND", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(line("0", 1, "VND", 0).lineTotal()).isEqualByComparingTo("0");
    }
}
