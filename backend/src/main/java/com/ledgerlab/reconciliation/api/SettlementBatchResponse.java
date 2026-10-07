package com.ledgerlab.reconciliation.api;

import com.ledgerlab.reconciliation.Classification;
import com.ledgerlab.reconciliation.SettlementBatch;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record SettlementBatchResponse(
        UUID id,
        String fileName,
        String contentSha256,
        LocalDate periodStart,
        LocalDate periodEnd,
        int recordCount,
        Map<Classification, Integer> counts,
        int exceptionCount,
        UUID uploadedBy,
        Instant uploadedAt) {

    public static SettlementBatchResponse from(SettlementBatch batch) {
        return new SettlementBatchResponse(
                batch.getId(),
                batch.getFileName(),
                batch.getContentSha256(),
                batch.getPeriodStart(),
                batch.getPeriodEnd(),
                batch.getRecordCount(),
                batch.counts(),
                batch.exceptionCount(),
                batch.getUploadedBy(),
                batch.getUploadedAt());
    }
}
