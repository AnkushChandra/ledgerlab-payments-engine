package com.ledgerlab.dispute;

import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditService;
import com.ledgerlab.dispute.api.DisputeResponse;
import com.ledgerlab.dispute.api.OpenDisputeRequest;
import com.ledgerlab.dispute.api.ResolveDisputeRequest;
import com.ledgerlab.ledger.LedgerAccountPurpose;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.ledger.LedgerTransactionType;
import com.ledgerlab.ledger.PostingLine;
import com.ledgerlab.ledger.PostingRequest;
import com.ledgerlab.payment.Payment;
import com.ledgerlab.payment.PaymentEvent;
import com.ledgerlab.payment.PaymentRepository;
import com.ledgerlab.payment.PaymentService;
import com.ledgerlab.payment.PaymentStatus;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.idempotency.IdempotencyService;
import com.ledgerlab.shared.idempotency.IdempotentRequest;
import com.ledgerlab.shared.idempotency.IdempotentResponse;
import com.ledgerlab.shared.idempotency.IdempotentResult;
import com.ledgerlab.shared.metrics.LedgerLabMetrics;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispute lifecycle. Opening freezes the disputed merchant funds in MERCHANT_DISPUTE_HOLD;
 * resolution either releases them to the merchant (won) or pays them to the customer (lost).
 * Lock order is always payment row, then dispute row, then ledger accounts.
 */
@Service
public class DisputeService {

    private final DisputeRepository disputes;
    private final PaymentService paymentService;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final LedgerLabMetrics metrics;
    private final Clock clock;

    public DisputeService(
            DisputeRepository disputes,
            PaymentService paymentService,
            PaymentRepository payments,
            LedgerService ledger,
            IdempotencyService idempotency,
            AuditService audit,
            LedgerLabMetrics metrics,
            Clock clock) {
        this.disputes = disputes;
        this.paymentService = paymentService;
        this.payments = payments;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public IdempotentResult<DisputeResponse> open(
            CurrentActor actor, UUID paymentId, OpenDisputeRequest request, String idempotencyKey) {
        var idempotent =
                IdempotentRequest.of(actor.organizationId(), "DISPUTE_OPEN", idempotencyKey, paymentId, request);
        return idempotency.execute(idempotent, DisputeResponse.class, () -> {
            Payment payment = paymentService.lock(actor.organizationId(), paymentId);
            long amount = request.amountMinor() != null ? request.amountMinor() : payment.refundableMinor();
            Instant now = clock.instant();
            PaymentStatus from = payment.openDispute(amount, now);

            var parties = paymentService.parties(payment);
            UUID disputeId = UUID.randomUUID();
            UUID journal = post(
                    actor,
                    LedgerTransactionType.DISPUTE_OPENED,
                    "Dispute hold on " + parties.merchant().name(),
                    disputeId,
                    DisputeAccounting.opened(
                            parties.merchant().ledgerAccount(LedgerAccountPurpose.MERCHANT_AVAILABLE),
                            parties.merchant().ledgerAccount(LedgerAccountPurpose.MERCHANT_DISPUTE_HOLD),
                            amount));
            Dispute dispute = disputes.save(new Dispute(
                    disputeId, paymentId, actor.organizationId(), amount, request.reason().trim(), actor.userId(), now));
            paymentService.appendEvent(new PaymentEvent(
                    payment, PaymentEvent.Type.DISPUTE_OPENED, amount, from, journal, actor.userId(),
                    dispute.getReason(), now));
            audit.record(
                    actor,
                    AuditAction.DISPUTE_OPENED,
                    "DISPUTE",
                    disputeId,
                    Map.of("paymentId", paymentId, "amountMinor", amount));
            metrics.paymentOperation("dispute_open", "success");
            return IdempotentResponse.created(DisputeResponse.from(dispute, payment), disputeId);
        });
    }

    @Transactional
    public IdempotentResult<DisputeResponse> resolve(
            CurrentActor actor, UUID disputeId, ResolveDisputeRequest request, String idempotencyKey) {
        var idempotent =
                IdempotentRequest.of(actor.organizationId(), "DISPUTE_RESOLVE", idempotencyKey, disputeId, request);
        return idempotency.execute(idempotent, DisputeResponse.class, () -> {
            UUID paymentId = disputes.findByIdAndOrganizationId(disputeId, actor.organizationId())
                    .map(Dispute::getPaymentId)
                    .orElseThrow(() -> ApiException.notFound("Dispute"));
            Payment payment = paymentService.lock(actor.organizationId(), paymentId);
            Dispute dispute = disputes.lockByIdAndOrganizationId(disputeId, actor.organizationId())
                    .orElseThrow(() -> ApiException.notFound("Dispute"));

            Instant now = clock.instant();
            boolean merchantWon = request.outcome() == Dispute.Outcome.WON;
            dispute.resolve(request.outcome(), actor.userId(), request.note(), now);
            PaymentStatus from = payment.getStatus();
            payment.resolveDispute(merchantWon, now);

            var parties = paymentService.parties(payment);
            UUID hold = parties.merchant().ledgerAccount(LedgerAccountPurpose.MERCHANT_DISPUTE_HOLD);
            List<PostingLine> lines = merchantWon
                    ? DisputeAccounting.won(
                            hold,
                            parties.merchant().ledgerAccount(LedgerAccountPurpose.MERCHANT_AVAILABLE),
                            dispute.getAmountMinor())
                    : DisputeAccounting.lost(
                            hold,
                            parties.customer().ledgerAccount(LedgerAccountPurpose.CUSTOMER_AVAILABLE),
                            dispute.getAmountMinor());
            UUID journal = post(
                    actor,
                    merchantWon ? LedgerTransactionType.DISPUTE_WON : LedgerTransactionType.DISPUTE_LOST,
                    (merchantWon ? "Dispute won by " : "Chargeback from ") + parties.merchant().name(),
                    disputeId,
                    lines);
            paymentService.appendEvent(new PaymentEvent(
                    payment,
                    merchantWon ? PaymentEvent.Type.DISPUTE_WON : PaymentEvent.Type.DISPUTE_LOST,
                    dispute.getAmountMinor(),
                    from,
                    journal,
                    actor.userId(),
                    request.note(),
                    now));
            audit.record(
                    actor,
                    AuditAction.DISPUTE_RESOLVED,
                    "DISPUTE",
                    disputeId,
                    Map.of("paymentId", paymentId, "outcome", request.outcome(), "amountMinor",
                            dispute.getAmountMinor()));
            metrics.paymentOperation("dispute_resolve", merchantWon ? "won" : "lost");
            return IdempotentResponse.ok(DisputeResponse.from(dispute, payment), disputeId);
        });
    }

    @Transactional(readOnly = true)
    public DisputeResponse get(UUID organizationId, UUID disputeId) {
        Dispute dispute = disputes.findByIdAndOrganizationId(disputeId, organizationId)
                .orElseThrow(() -> ApiException.notFound("Dispute"));
        Payment payment = payments.findByIdAndOrganizationId(dispute.getPaymentId(), organizationId)
                .orElseThrow();
        return DisputeResponse.from(dispute, payment);
    }

    @Transactional(readOnly = true)
    public PageResponse<DisputeResponse> search(UUID organizationId, Dispute.Status status, Pageable pageable) {
        Page<Dispute> page = disputes.search(organizationId, status, pageable);
        Map<UUID, Payment> paymentsById = payments
                .findByOrganizationIdAndIdIn(
                        organizationId,
                        page.getContent().stream().map(Dispute::getPaymentId).toList())
                .stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity()));
        return PageResponse.of(page, d -> DisputeResponse.from(d, paymentsById.get(d.getPaymentId())));
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> forPayment(UUID organizationId, UUID paymentId) {
        Payment payment = payments.findByIdAndOrganizationId(paymentId, organizationId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
        return disputes.findByOrganizationIdAndPaymentIdOrderByOpenedAtDesc(organizationId, paymentId).stream()
                .map(d -> DisputeResponse.from(d, payment))
                .toList();
    }

    private UUID post(
            CurrentActor actor, LedgerTransactionType type, String description, UUID disputeId, List<PostingLine> lines) {
        return ledger.post(new PostingRequest(
                actor.organizationId(), type, description, "DISPUTE", disputeId, actor.userId(), lines));
    }
}
