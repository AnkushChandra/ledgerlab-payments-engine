package com.ledgerlab.account;

import com.ledgerlab.ledger.LedgerAccountPurpose;
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
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "financial_account")
public class FinancialAccount extends AbstractEntity {

    public enum Type {
        CUSTOMER(LedgerAccountPurpose.CUSTOMER_AVAILABLE, LedgerAccountPurpose.CUSTOMER_RESERVED),
        MERCHANT(LedgerAccountPurpose.MERCHANT_AVAILABLE, LedgerAccountPurpose.MERCHANT_DISPUTE_HOLD);

        private final LedgerAccountPurpose available;
        private final LedgerAccountPurpose held;

        Type(LedgerAccountPurpose available, LedgerAccountPurpose held) {
            this.available = available;
            this.held = held;
        }

        public LedgerAccountPurpose availablePurpose() {
            return available;
        }

        public List<LedgerAccountPurpose> ledgerPurposes() {
            return List.of(available, held);
        }
    }

    public enum Status {
        ACTIVE,
        FROZEN,
        CLOSED
    }

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false)
    private Type type;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    private long version;

    protected FinancialAccount() {}

    public FinancialAccount(
            UUID id, UUID organizationId, Type type, String name, String reference, UUID createdBy, Instant createdAt) {
        super(id);
        this.organizationId = organizationId;
        this.type = type;
        this.name = name;
        this.reference = reference;
        this.status = Status.ACTIVE;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** Allowed: ACTIVE ↔ FROZEN, and ACTIVE/FROZEN → CLOSED. CLOSED is terminal. */
    public void changeStatus(Status target) {
        if (status == target) {
            return;
        }
        if (status == Status.CLOSED) {
            throw new ApiException(ErrorCode.INVALID_STATUS_CHANGE, "A closed account cannot be reopened.");
        }
        this.status = target;
    }

    public void requireActive() {
        if (status != Status.ACTIVE) {
            throw new ApiException(
                    ErrorCode.ACCOUNT_NOT_ACTIVE, "Account '" + name + "' is " + status + " and cannot move funds.");
        }
    }

    public void requireType(Type expected, String role) {
        if (type != expected) {
            throw new ApiException(
                    ErrorCode.INVALID_ACCOUNT_TYPE, "The " + role + " account must be a " + expected + " account.");
        }
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getReference() {
        return reference;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
