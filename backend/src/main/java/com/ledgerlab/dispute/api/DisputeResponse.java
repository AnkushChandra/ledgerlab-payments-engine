package com.ledgerlab.dispute.api;

import com.ledgerlab.dispute.Dispute;
import com.ledgerlab.payment.Payment;
import com.ledgerlab.payment.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record DisputeResponse(
        UUID id,
        UUID paymentId,
        long amountMinor,
        String currency,
        String reason,
        Dispute.Status status,
        UUID openedBy,
        Instant openedAt,
        UUID resolvedBy,
        Instant resolvedAt,
        String resolutionNote,
        PaymentSummary payment) {

    public record PaymentSummary(
            UUID id,
            UUID customerAccountId,
            UUID merchantAccountId,
            String reference,
            PaymentStatus status,
            long capturedAmountMinor,
            long refundedAmountMinor) {}

    public static DisputeResponse from(Dispute dispute, Payment payment) {
        return new DisputeResponse(
                dispute.getId(),
                dispute.getPaymentId(),
                dispute.getAmountMinor(),
                dispute.getCurrency(),
                dispute.getReason(),
                dispute.getStatus(),
                dispute.getOpenedBy(),
                dispute.getOpenedAt(),
                dispute.getResolvedBy(),
                dispute.getResolvedAt(),
                dispute.getResolutionNote(),
                new PaymentSummary(
                        payment.getId(),
                        payment.getCustomerAccountId(),
                        payment.getMerchantAccountId(),
                        payment.getReference(),
                        payment.getStatus(),
                        payment.getCapturedAmountMinor(),
                        payment.getRefundedAmountMinor()));
    }
}
