package com.ledgerlab.reconciliation;

import com.ledgerlab.shared.persistence.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "settlement_batch")
public class SettlementBatch extends AbstractEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_sha256", nullable = false)
    private String contentSha256;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "missing_internal_count", nullable = false)
    private int missingInternalCount;

    @Column(name = "missing_external_count", nullable = false)
    private int missingExternalCount;

    @Column(name = "amount_mismatch_count", nullable = false)
    private int amountMismatchCount;

    @Column(name = "status_mismatch_count", nullable = false)
    private int statusMismatchCount;

    @Column(name = "duplicate_external_count", nullable = false)
    private int duplicateExternalCount;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected SettlementBatch() {}

    public SettlementBatch(
            UUID id,
            UUID organizationId,
            String fileName,
            String contentSha256,
            LocalDate periodStart,
            LocalDate periodEnd,
            int recordCount,
            List<ReconciliationEngine.Result> results,
            UUID uploadedBy,
            Instant uploadedAt) {
        super(id);
        this.organizationId = organizationId;
        this.fileName = fileName;
        this.contentSha256 = contentSha256;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.recordCount = recordCount;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
        for (ReconciliationEngine.Result result : results) {
            switch (result.classification()) {
                case MATCHED -> matchedCount++;
                case MISSING_INTERNAL -> missingInternalCount++;
                case MISSING_EXTERNAL -> missingExternalCount++;
                case AMOUNT_MISMATCH -> amountMismatchCount++;
                case STATUS_MISMATCH -> statusMismatchCount++;
                case DUPLICATE_EXTERNAL -> duplicateExternalCount++;
            }
        }
    }

    public Map<Classification, Integer> counts() {
        Map<Classification, Integer> counts = new EnumMap<>(Classification.class);
        counts.put(Classification.MATCHED, matchedCount);
        counts.put(Classification.MISSING_INTERNAL, missingInternalCount);
        counts.put(Classification.MISSING_EXTERNAL, missingExternalCount);
        counts.put(Classification.AMOUNT_MISMATCH, amountMismatchCount);
        counts.put(Classification.STATUS_MISMATCH, statusMismatchCount);
        counts.put(Classification.DUPLICATE_EXTERNAL, duplicateExternalCount);
        return counts;
    }

    public int exceptionCount() {
        return missingInternalCount + missingExternalCount + amountMismatchCount + statusMismatchCount
                + duplicateExternalCount;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
