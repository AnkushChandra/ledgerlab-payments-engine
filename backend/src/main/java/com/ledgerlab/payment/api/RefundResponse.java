package com.ledgerlab.payment.api;

import com.ledgerlab.payment.Refund;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID paymentId,
        long amountMinor,
        String currency,
        String reason,
        UUID ledgerTransactionId,
        UUID createdBy,
        Instant createdAt) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getAmountMinor(),
                refund.getCurrency(),
                refund.getReason(),
                refund.getLedgerTransactionId(),
                refund.getCreatedBy(),
                refund.getCreatedAt());
    }
}
