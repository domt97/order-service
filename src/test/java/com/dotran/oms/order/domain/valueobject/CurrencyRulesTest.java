package com.dotran.oms.order.domain.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyRulesTest {

    @Test
    @DisplayName("ISO currencies give their minor units")
    void known() {
        assertThat(CurrencyRules.minorUnits("VND")).contains(0);
        assertThat(CurrencyRules.minorUnits("USD")).contains(2);
        assertThat(CurrencyRules.minorUnits("KWD")).contains(3);
    }

    @Test
    @DisplayName("unknown, non-monetary (XXX, XAU) and null codes are empty (D7)")
    void refused() {
        assertThat(CurrencyRules.minorUnits("XYZ")).isEmpty();
        assertThat(CurrencyRules.minorUnits("XXX")).isEmpty();
        assertThat(CurrencyRules.minorUnits("XAU")).isEmpty();
        assertThat(CurrencyRules.minorUnits("vnd")).isEmpty();
        assertThat(CurrencyRules.minorUnits(null)).isEmpty();
    }
}
