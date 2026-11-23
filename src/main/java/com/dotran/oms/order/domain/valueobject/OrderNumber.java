package com.dotran.oms.order.domain.valueobject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * The human order number {@code {storeCode}-{yyMMdd}-{seq}} (BR-ORD-10), unique per store. The day is the store's local
 * day; the sequence has at least three digits and grows past 999.
 */
public final class OrderNumber {

    private static final Pattern STORE_CODE = Pattern.compile("[A-Z0-9]{1,20}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private OrderNumber() {
    }

    /** BR-STO-01; stores-service does not enforce it yet (D4), so placement checks it before using a code. */
    public static boolean validStoreCode(String code) {
        return code != null && STORE_CODE.matcher(code).matches();
    }

    public static String of(String storeCode, LocalDate localDay, int seq) {
        if (!validStoreCode(storeCode)) {
            throw new IllegalArgumentException("Store code not [A-Z0-9]{1,20}");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be >= 1");
        }
        return storeCode + "-" + DAY.format(localDay) + "-" + String.format("%03d", seq);
    }
}
