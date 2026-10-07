package com.ledgerlab.payment;

import com.ledgerlab.shared.persistence.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "refund")
public class Refund extends AbstractEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    private String reason;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Refund() {}

    public Refund(
            UUID id,
            Payment payment,
            long amountMinor,
            String reason,
            UUID ledgerTransactionId,
            UUID createdBy,
            Instant createdAt) {
        super(id);
        this.paymentId = payment.getId();
        this.organizationId = payment.getOrganizationId();
        this.amountMinor = amountMinor;
        this.currency = payment.getCurrency();
        this.reason = reason;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getPaymentId() {
        return paymentId;
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

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
