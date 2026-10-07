package com.ledgerlab.payment;

public enum PaymentStatus {
    AUTHORIZED,
    PARTIALLY_CAPTURED,
    CAPTURED,
    VOIDED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    DISPUTED,
    RESOLVED,
    FAILED;

    public boolean isTerminal() {
        return this == VOIDED || this == REFUNDED || this == RESOLVED || this == FAILED;
    }
}
