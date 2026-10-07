package com.ledgerlab.payment;

import com.ledgerlab.shared.persistence.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only entry in a payment's timeline (also enforced by a database trigger). */
@Entity
@Immutable
@Table(name = "payment_event")
public class PaymentEvent extends AbstractEntity {

    public enum Type {
        AUTHORIZED,
        AUTHORIZATION_FAILED,
        CAPTURED,
        VOIDED,
        REFUNDED,
        DISPUTE_OPENED,
        DISPUTE_WON,
        DISPUTE_LOST
    }

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private Type type;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PaymentStatus toStatus;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentEvent() {}

    public PaymentEvent(
            Payment payment,
            Type type,
            long amountMinor,
            PaymentStatus fromStatus,
            UUID ledgerTransactionId,
            UUID actorUserId,
            String note,
            Instant createdAt) {
        super(UUID.randomUUID());
        this.paymentId = payment.getId();
        this.organizationId = payment.getOrganizationId();
        this.type = type;
        this.amountMinor = amountMinor;
        this.fromStatus = fromStatus;
        this.toStatus = payment.getStatus();
        this.ledgerTransactionId = ledgerTransactionId;
        this.actorUserId = actorUserId;
        this.note = note;
        this.createdAt = createdAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Type getType() {
        return type;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
