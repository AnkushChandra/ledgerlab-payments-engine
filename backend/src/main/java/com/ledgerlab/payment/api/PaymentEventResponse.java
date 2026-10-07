package com.ledgerlab.payment.api;

import com.ledgerlab.payment.PaymentEvent;
import com.ledgerlab.payment.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentEventResponse(
        UUID id,
        PaymentEvent.Type type,
        long amountMinor,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        UUID ledgerTransactionId,
        UUID actorUserId,
        String note,
        Instant createdAt) {

    public static PaymentEventResponse from(PaymentEvent event) {
        return new PaymentEventResponse(
                event.getId(),
                event.getType(),
                event.getAmountMinor(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getLedgerTransactionId(),
                event.getActorUserId(),
                event.getNote(),
                event.getCreatedAt());
    }
}
