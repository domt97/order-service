package com.dotran.oms.order.domain.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderNumberTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Test
    @DisplayName("{storeCode}-{yyMMdd}-{seq}: at least three digits, grows past 999")
    void format() {
        assertThat(OrderNumber.of("HCM01", DAY, 1)).isEqualTo("HCM01-261121-001");
        assertThat(OrderNumber.of("HCM01", DAY, 999)).isEqualTo("HCM01-261121-999");
        assertThat(OrderNumber.of("HCM01", DAY, 1000)).isEqualTo("HCM01-261121-1000");
    }

    @Test
    @DisplayName("store code must be [A-Z0-9]{1,20} (BR-STO-01, D4)")
    void storeCode() {
        assertThat(OrderNumber.validStoreCode("A")).isTrue();
        assertThat(OrderNumber.validStoreCode("A".repeat(20))).isTrue();
        assertThat(OrderNumber.validStoreCode("A".repeat(21))).isFalse();
        assertThat(OrderNumber.validStoreCode("q-1")).isFalse();
        assertThat(OrderNumber.validStoreCode("")).isFalse();
        assertThat(OrderNumber.validStoreCode(null)).isFalse();
        assertThatThrownBy(() -> OrderNumber.of("q-1", DAY, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderNumber.of("A", DAY, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
