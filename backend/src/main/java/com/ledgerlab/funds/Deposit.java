package com.ledgerlab.funds;

import com.ledgerlab.shared.persistence.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A simulated external bank deposit: the only way money enters LedgerLab. */
@Entity
@Immutable
@Table(name = "deposit")
public class Deposit extends AbstractEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "financial_account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    private String memo;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Deposit() {}

    public Deposit(
            UUID id,
            UUID organizationId,
            UUID accountId,
            long amountMinor,
            String memo,
            UUID ledgerTransactionId,
            UUID createdBy,
            Instant createdAt) {
        super(id);
        this.organizationId = organizationId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = "USD";
        this.memo = memo;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMemo() {
        return memo;
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
