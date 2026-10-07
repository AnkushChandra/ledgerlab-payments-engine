package com.ledgerlab.payment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentFilter(
        List<PaymentStatus> statuses,
        UUID customerAccountId,
        UUID merchantAccountId,
        Instant createdFrom,
        Instant createdTo,
        String query) {}
