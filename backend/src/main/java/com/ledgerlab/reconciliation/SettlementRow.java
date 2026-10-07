package com.ledgerlab.reconciliation;

import java.time.Instant;
import java.util.UUID;

/** A validated settlement CSV row. {@code rowNumber} is the 1-based line number in the file. */
public record SettlementRow(
        int rowNumber,
        String processorRecordId,
        UUID paymentId,
        String status,
        long amountMinor,
        String currency,
        Instant settledAt) {}
