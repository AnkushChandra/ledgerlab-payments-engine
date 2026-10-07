package com.ledgerlab.payment;

import com.ledgerlab.shared.Money;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Payment aggregate. All amount rules and status derivation live here and in
 * {@link PaymentStateMachine}; services only orchestrate locking, ledger posting and persistence.
 * Methods validate before mutating, so a rejected operation leaves the aggregate unchanged.
 */
@Entity
@Table(name = "payment")
public class Payment extends AbstractEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "customer_account_id", nullable = false, updatable = false)
    private UUID customerAccountId;

    @Column(name = "merchant_account_id", nullable = false, updatable = false)
    private UUID merchantAccountId;

    @Column(updatable = false)
    private String reference;

    @Column(updatable = false)
    private String description;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "authorized_amount_minor", nullable = false, updatable = false)
    private long authorizedAmountMinor;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "released_amount_minor", nullable = false)
    private long releasedAmountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "disputed_amount_minor", nullable = false)
    private long disputedAmountMinor;

    @Column(name = "dispute_lost_amount_minor", nullable = false)
    private long disputeLostAmountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_code", updatable = false)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "first_captured_at")
    private Instant firstCapturedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    private long version;

    protected Payment() {}

    private Payment(
            UUID id,
            UUID organizationId,
            UUID customerAccountId,
            UUID merchantAccountId,
            long amountMinor,
            String reference,
            String description,
            PaymentStatus status,
            String failureCode,
            UUID createdBy,
            Instant now) {
        super(id);
        if (!Money.isValidOperationAmount(amountMinor)) {
            throw new IllegalArgumentException("Invalid authorization amount " + amountMinor);
        }
        this.organizationId = organizationId;
        this.customerAccountId = customerAccountId;
        this.merchantAccountId = merchantAccountId;
        this.authorizedAmountMinor = amountMinor;
        this.reference = reference;
        this.description = description;
        this.currency = Money.CURRENCY;
        this.status = status;
        this.failureCode = failureCode;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Payment authorized(
            UUID id,
            UUID organizationId,
            UUID customerAccountId,
            UUID merchantAccountId,
            long amountMinor,
            String reference,
            String description,
            UUID createdBy,
            Instant now) {
        return new Payment(
                id,
                organizationId,
                customerAccountId,
                merchantAccountId,
                amountMinor,
                reference,
                description,
                PaymentStatus.AUTHORIZED,
                null,
                createdBy,
                now);
    }

    public static Payment declined(
            UUID id,
            UUID organizationId,
            UUID customerAccountId,
            UUID merchantAccountId,
            long amountMinor,
            String reference,
            String description,
            String failureCode,
            UUID createdBy,
            Instant now) {
        return new Payment(
                id,
                organizationId,
                customerAccountId,
                merchantAccountId,
                amountMinor,
                reference,
                description,
                PaymentStatus.FAILED,
                failureCode,
                createdBy,
                now);
    }

    /** Captured and released amounts produced by a capture. */
    public record CaptureResult(PaymentStatus fromStatus, long capturedMinor, long releasedMinor) {}

    public CaptureResult capture(long amountMinor, boolean finalCapture, Instant now) {
        PaymentStateMachine.require(status, PaymentAction.CAPTURE);
        requirePositive(amountMinor);
        long remaining = remainingAuthorizationMinor();
        if (amountMinor > remaining) {
            throw new ApiException(
                    ErrorCode.CAPTURE_EXCEEDS_AUTHORIZATION,
                    "Capture of " + Money.format(amountMinor) + " exceeds the remaining authorization of "
                            + Money.format(remaining) + ".");
        }
        PaymentStatus from = status;
        capturedAmountMinor += amountMinor;
        long released = 0;
        if (finalCapture) {
            released = remainingAuthorizationMinor();
            releasedAmountMinor += released;
        }
        status = remainingAuthorizationMinor() == 0 ? PaymentStatus.CAPTURED : PaymentStatus.PARTIALLY_CAPTURED;
        if (firstCapturedAt == null) {
            firstCapturedAt = now;
        }
        updatedAt = now;
        return new CaptureResult(from, amountMinor, released);
    }

    /** Voids an uncaptured authorization and returns the amount released back to the customer. */
    public long voidAuthorization(Instant now) {
        PaymentStateMachine.require(status, PaymentAction.VOID);
        if (capturedAmountMinor != 0) {
            throw new ApiException(ErrorCode.INVALID_PAYMENT_STATE, "Only uncaptured authorizations can be voided.");
        }
        long released = remainingAuthorizationMinor();
        releasedAmountMinor += released;
        status = PaymentStatus.VOIDED;
        updatedAt = now;
        return released;
    }

    public PaymentStatus refund(long amountMinor, Instant now) {
        PaymentStateMachine.require(status, PaymentAction.REFUND);
        requirePositive(amountMinor);
        long refundable = refundableMinor();
        if (amountMinor > refundable) {
            throw new ApiException(
                    ErrorCode.REFUND_EXCEEDS_CAPTURED,
                    "Refund of " + Money.format(amountMinor) + " exceeds the refundable amount of "
                            + Money.format(refundable) + ".");
        }
        PaymentStatus from = status;
        refundedAmountMinor += amountMinor;
        status = refundableMinor() == 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        updatedAt = now;
        return from;
    }

    public PaymentStatus openDispute(long amountMinor, Instant now) {
        PaymentStateMachine.require(status, PaymentAction.OPEN_DISPUTE);
        requirePositive(amountMinor);
        long refundable = refundableMinor();
        if (amountMinor > refundable) {
            throw new ApiException(
                    ErrorCode.DISPUTE_EXCEEDS_REFUNDABLE,
                    "Dispute of " + Money.format(amountMinor) + " exceeds the disputable amount of "
                            + Money.format(refundable) + ".");
        }
        PaymentStatus from = status;
        disputedAmountMinor = amountMinor;
        status = PaymentStatus.DISPUTED;
        updatedAt = now;
        return from;
    }

    /** Resolves the open dispute; when the merchant loses, the disputed amount is charged back. */
    public void resolveDispute(boolean merchantWon, Instant now) {
        PaymentStateMachine.require(status, PaymentAction.RESOLVE_DISPUTE);
        if (!merchantWon) {
            disputeLostAmountMinor += disputedAmountMinor;
        }
        status = PaymentStatus.RESOLVED;
        updatedAt = now;
    }

    public long remainingAuthorizationMinor() {
        return authorizedAmountMinor - capturedAmountMinor - releasedAmountMinor;
    }

    public long refundableMinor() {
        return capturedAmountMinor - refundedAmountMinor - disputeLostAmountMinor;
    }

    /** What the processor should report as settled for this payment. */
    public long netSettledMinor() {
        return capturedAmountMinor - refundedAmountMinor - disputeLostAmountMinor;
    }

    /** Actions the payment can accept now, considering both status and remaining amounts. */
    public Set<PaymentAction> allowedActions() {
        Set<PaymentAction> actions = PaymentStateMachine.allowedActions(status);
        if (remainingAuthorizationMinor() == 0) {
            actions.remove(PaymentAction.CAPTURE);
        }
        if (refundableMinor() == 0) {
            actions.removeAll(EnumSet.of(PaymentAction.REFUND, PaymentAction.OPEN_DISPUTE));
        }
        return actions;
    }

    private static void requirePositive(long amountMinor) {
        if (!Money.isValidOperationAmount(amountMinor)) {
            throw new IllegalArgumentException("Amount must be between 1 and " + Money.MAX_OPERATION_AMOUNT);
        }
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getCustomerAccountId() {
        return customerAccountId;
    }

    public UUID getMerchantAccountId() {
        return merchantAccountId;
    }

    public String getReference() {
        return reference;
    }

    public String getDescription() {
        return description;
    }

    public String getCurrency() {
        return currency;
    }

    public long getAuthorizedAmountMinor() {
        return authorizedAmountMinor;
    }

    public long getCapturedAmountMinor() {
        return capturedAmountMinor;
    }

    public long getReleasedAmountMinor() {
        return releasedAmountMinor;
    }

    public long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public long getDisputedAmountMinor() {
        return disputedAmountMinor;
    }

    public long getDisputeLostAmountMinor() {
        return disputeLostAmountMinor;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFirstCapturedAt() {
        return firstCapturedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
