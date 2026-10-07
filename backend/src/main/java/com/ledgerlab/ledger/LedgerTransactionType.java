package com.ledgerlab.ledger;

public enum LedgerTransactionType {
    /** The only transaction type allowed to create money (by posting to EXTERNAL_CLEARING). */
    SIMULATED_DEPOSIT,
    TRANSFER,
    PAYMENT_AUTHORIZATION,
    PAYMENT_CAPTURE,
    PAYMENT_VOID,
    PAYMENT_REFUND,
    DISPUTE_OPENED,
    DISPUTE_WON,
    DISPUTE_LOST
}
