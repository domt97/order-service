package com.dotran.oms.order.domain.valueobject;

import java.util.Currency;
import java.util.Optional;

/**
 * Minor units of a currency, for rounding line amounts (BR-ORD-04). Empty for a code that is not ISO 4217 and for one
 * without minor units (XXX, XAU: fraction digits -1), so such a currency is refused instead of rounding to tens.
 */
public final class CurrencyRules {

    private CurrencyRules() {
    }

    public static Optional<Integer> minorUnits(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            int digits = Currency.getInstance(code).getDefaultFractionDigits();
            return digits < 0 ? Optional.empty() : Optional.of(digits);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
