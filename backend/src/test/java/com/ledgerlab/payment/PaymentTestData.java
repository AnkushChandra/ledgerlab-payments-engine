package com.ledgerlab.payment;

import java.time.Instant;
import java.util.UUID;

/** Builds payments in any status through legitimate domain transitions only. */
final class PaymentTestData {

    static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    static final long AUTHORIZED = 10_000;

    private PaymentTestData() {}

    static Payment authorized(long amount) {
        return Payment.authorized(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amount, "REF", null, null,
                NOW);
    }

    static Payment in(PaymentStatus status) {
        Payment payment = authorized(AUTHORIZED);
        switch (status) {
            case AUTHORIZED -> {}
            case PARTIALLY_CAPTURED -> payment.capture(4_000, false, NOW);
            case CAPTURED -> payment.capture(AUTHORIZED, false, NOW);
            case VOIDED -> payment.voidAuthorization(NOW);
            case PARTIALLY_REFUNDED -> {
                payment.capture(AUTHORIZED, false, NOW);
                payment.refund(1_000, NOW);
            }
            case REFUNDED -> {
                payment.capture(AUTHORIZED, false, NOW);
                payment.refund(AUTHORIZED, NOW);
            }
            case DISPUTED -> {
                payment.capture(AUTHORIZED, false, NOW);
                payment.openDispute(AUTHORIZED, NOW);
            }
            case RESOLVED -> {
                payment.capture(AUTHORIZED, false, NOW);
                payment.openDispute(AUTHORIZED, NOW);
                payment.resolveDispute(true, NOW);
            }
            case FAILED -> payment = Payment.declined(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AUTHORIZED, null, null,
                    "INSUFFICIENT_FUNDS", null, NOW);
        }
        if (payment.getStatus() != status) {
            throw new IllegalStateException("Fixture produced " + payment.getStatus() + " instead of " + status);
        }
        return payment;
    }
}
