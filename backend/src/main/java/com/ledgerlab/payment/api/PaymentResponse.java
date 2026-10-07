package com.ledgerlab.payment.api;

import com.ledgerlab.payment.Payment;
import com.ledgerlab.payment.PaymentAction;
import com.ledgerlab.payment.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID customerAccountId,
        UUID merchantAccountId,
        String reference,
        String description,
        String currency,
        PaymentStatus status,
        String failureCode,
        long authorizedAmountMinor,
        long capturedAmountMinor,
        long releasedAmountMinor,
        long refundedAmountMinor,
        long disputedAmountMinor,
        long disputeLostAmountMinor,
        long remainingAuthorizationMinor,
        long refundableMinor,
        long netSettledMinor,
        List<PaymentAction> allowedActions,
        Instant createdAt,
        Instant firstCapturedAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getCustomerAccountId(),
                p.getMerchantAccountId(),
                p.getReference(),
                p.getDescription(),
                p.getCurrency(),
                p.getStatus(),
                p.getFailureCode(),
                p.getAuthorizedAmountMinor(),
                p.getCapturedAmountMinor(),
                p.getReleasedAmountMinor(),
                p.getRefundedAmountMinor(),
                p.getDisputedAmountMinor(),
                p.getDisputeLostAmountMinor(),
                p.remainingAuthorizationMinor(),
                p.refundableMinor(),
                p.netSettledMinor(),
                p.allowedActions().stream().sorted().toList(),
                p.getCreatedAt(),
                p.getFirstCapturedAt(),
                p.getUpdatedAt());
    }
}
