package com.ledgerlab.dispute;

import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.persistence.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispute")
public class Dispute extends AbstractEntity {

    public enum Status {
        OPEN,
        WON,
        LOST
    }

    public enum Outcome {
        WON,
        LOST
    }

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "opened_by", updatable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Version
    private long version;

    protected Dispute() {}

    public Dispute(
            UUID id, UUID paymentId, UUID organizationId, long amountMinor, String reason, UUID openedBy, Instant now) {
        super(id);
        this.paymentId = paymentId;
        this.organizationId = organizationId;
        this.amountMinor = amountMinor;
        this.currency = "USD";
        this.reason = reason;
        this.status = Status.OPEN;
        this.openedBy = openedBy;
        this.openedAt = now;
    }

    public void resolve(Outcome outcome, UUID resolvedBy, String note, Instant now) {
        if (status != Status.OPEN) {
            throw new ApiException(ErrorCode.INVALID_DISPUTE_STATE, "This dispute is already " + status + ".");
        }
        this.status = outcome == Outcome.WON ? Status.WON : Status.LOST;
        this.resolvedBy = resolvedBy;
        this.resolutionNote = note;
        this.resolvedAt = now;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReason() {
        return reason;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }
}
