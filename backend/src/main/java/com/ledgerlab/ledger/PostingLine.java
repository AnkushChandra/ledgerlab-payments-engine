package com.ledgerlab.ledger;

import java.util.Objects;
import java.util.UUID;

/** One side of a journal: positive amounts are debits, negative amounts are credits. */
public record PostingLine(UUID ledgerAccountId, long amountMinor) {

    public PostingLine {
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
        if (amountMinor == 0) {
            throw new IllegalArgumentException("Posting line amount must not be zero");
        }
    }

    public static PostingLine debit(UUID ledgerAccountId, long amountMinor) {
        requirePositive(amountMinor);
        return new PostingLine(ledgerAccountId, amountMinor);
    }

    public static PostingLine credit(UUID ledgerAccountId, long amountMinor) {
        requirePositive(amountMinor);
        return new PostingLine(ledgerAccountId, -amountMinor);
    }

    private static void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Debit/credit amount must be positive, got " + amountMinor);
        }
    }
}
