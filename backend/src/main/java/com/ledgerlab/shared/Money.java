package com.ledgerlab.shared;

import java.util.Locale;

/**
 * USD helpers. All amounts are {@code long} minor units (cents); floating point is never used for
 * money anywhere in LedgerLab.
 */
public final class Money {

    public static final String CURRENCY = "USD";
    public static final long MIN_OPERATION_AMOUNT = 1;
    public static final long MAX_OPERATION_AMOUNT = 100_000_000; // $1,000,000.00

    private Money() {}

    public static boolean isValidOperationAmount(long amountMinor) {
        return amountMinor >= MIN_OPERATION_AMOUNT && amountMinor <= MAX_OPERATION_AMOUNT;
    }

    /** Formats minor units for human-readable messages, e.g. {@code 123456 -> "$1,234.56"}. */
    public static String format(long amountMinor) {
        long abs = Math.abs(amountMinor);
        String formatted = String.format(Locale.US, "$%,d.%02d", abs / 100, abs % 100);
        return amountMinor < 0 ? "-" + formatted : formatted;
    }
}
