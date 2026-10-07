package com.ledgerlab.reconciliation;

public enum Classification {
    MATCHED,
    MISSING_INTERNAL,
    MISSING_EXTERNAL,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH,
    DUPLICATE_EXTERNAL
}
