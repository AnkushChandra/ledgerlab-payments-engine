package com.ledgerlab.payment;

import com.ledgerlab.account.AccountService;
import com.ledgerlab.account.AccountService.AccountHandle;
import com.ledgerlab.account.FinancialAccount;
import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditService;
import com.ledgerlab.ledger.LedgerAccountPurpose;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.ledger.LedgerTransactionType;
import com.ledgerlab.ledger.PostingLine;
import com.ledgerlab.ledger.PostingRequest;
import com.ledgerlab.payment.api.AuthorizePaymentRequest;
import com.ledgerlab.payment.api.CapturePaymentRequest;
import com.ledgerlab.payment.api.PaymentEventResponse;
import com.ledgerlab.payment.api.PaymentResponse;
import com.ledgerlab.payment.api.RefundPaymentRequest;
import com.ledgerlab.payment.api.RefundResponse;
import com.ledgerlab.payment.api.RefundResult;
import com.ledgerlab.payment.api.VoidPaymentRequest;
import com.ledgerlab.shared.Money;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates payment operations. Every mutation runs in one database transaction that: claims
 * the idempotency key, locks the payment row, applies the domain transition, posts the balanced
 * journal, appends the timeline event and audit event, and stores the idempotent response. Any
 * failure rolls back all of it.
 */
@Service
public class PaymentService {

    public static final String FAILURE_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String FAILURE_CUSTOMER_NOT_ACTIVE = "CUSTOMER_ACCOUNT_NOT_ACTIVE";

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final RefundRepository refunds;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final LedgerLabMetrics metrics;
    private final Clock clock;

    public PaymentService(
            PaymentRepository payments,
            PaymentEventRepository events,
            RefundRepository refunds,
            AccountService accounts,
            LedgerService ledger,
            IdempotencyService idempotency,
            AuditService audit,
            LedgerLabMetrics metrics,
            Clock clock) {
        this.payments = payments;
        this.events = events;
        this.refunds = refunds;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public IdempotentResult<PaymentResponse> authorize(
            CurrentActor actor, AuthorizePaymentRequest request, String idempotencyKey) {
        var idempotent = IdempotentRequest.of(actor.organizationId(), "PAYMENT_AUTHORIZE", idempotencyKey, request);
        return idempotency.execute(idempotent, PaymentResponse.class, () -> {
            AccountHandle customer = accounts.handle(actor.organizationId(), request.customerAccountId());
            AccountHandle merchant = accounts.handle(actor.organizationId(), request.merchantAccountId());
            customer.account().requireType(FinancialAccount.Type.CUSTOMER, "customer");
            merchant.account().requireType(FinancialAccount.Type.MERCHANT, "merchant");
            merchant.account().requireActive();

            UUID paymentId = UUID.randomUUID();
            Instant now = clock.instant();
            UUID available = customer.ledgerAccount(LedgerAccountPurpose.CUSTOMER_AVAILABLE);
            UUID reserved = customer.ledgerAccount(LedgerAccountPurpose.CUSTOMER_RESERVED);

            String failure = null;
            if (!customer.account().isActive()) {
                failure = FAILURE_CUSTOMER_NOT_ACTIVE;
            } else {
                // Lock both accounts the authorization will post to, in global order, before reading.
                var locked = ledger.lockAccounts(actor.organizationId(), List.of(available, reserved));
                if (locked.get(available).presentedBalance() < request.amountMinor()) {
                    failure = FAILURE_INSUFFICIENT_FUNDS;
                }
            }

            Payment payment;
            if (failure != null) {
                payment = Payment.declined(
                        paymentId,
                        actor.organizationId(),
                        customer.id(),
                        merchant.id(),
                        request.amountMinor(),
                        request.reference(),
                        request.description(),
                        failure,
                        actor.userId(),
                        now);
                payments.save(payment);
                events.save(new PaymentEvent(
                        payment, PaymentEvent.Type.AUTHORIZATION_FAILED, request.amountMinor(), null, null,
                        actor.userId(), failure, now));
                audit.record(
                        actor,
                        AuditAction.PAYMENT_AUTHORIZATION_FAILED,
                        "PAYMENT",
                        paymentId,
                        Map.of("amountMinor", request.amountMinor(), "failureCode", failure));
                metrics.paymentOperation("authorize", "declined");
            } else {
                payment = Payment.authorized(
                        paymentId,
                        actor.organizationId(),
                        customer.id(),
                        merchant.id(),
                        request.amountMinor(),
                        request.reference(),
                        request.description(),
                        actor.userId(),
                        now);
                UUID journal = post(
                        actor,
                        payment,
                        LedgerTransactionType.PAYMENT_AUTHORIZATION,
                        "Authorization hold for payment to " + merchant.name(),
                        PaymentAccounting.authorization(available, reserved, request.amountMinor()));
                payments.save(payment);
                events.save(new PaymentEvent(
                        payment, PaymentEvent.Type.AUTHORIZED, request.amountMinor(), null, journal, actor.userId(),
                        null, now));
                audit.record(
                        actor,
                        AuditAction.PAYMENT_AUTHORIZED,
                        "PAYMENT",
                        paymentId,
                        Map.of("amountMinor", request.amountMinor(), "merchantAccountId", merchant.id()));
                metrics.paymentOperation("authorize", "success");
            }
            return IdempotentResponse.created(PaymentResponse.from(payment), paymentId);
        });
    }

    @Transactional
    public IdempotentResult<PaymentResponse> capture(
            CurrentActor actor, UUID paymentId, CapturePaymentRequest request, String idempotencyKey) {
        var normalized = new CapturePaymentRequest(request.amountMinor(), request.isFinal());
        var idempotent =
                IdempotentRequest.of(actor.organizationId(), "PAYMENT_CAPTURE", idempotencyKey, paymentId, normalized);
        return idempotency.execute(idempotent, PaymentResponse.class, () -> {
            Payment payment = lock(actor.organizationId(), paymentId);
            Parties parties = parties(payment);
            parties.customer().account().requireActive();
            parties.merchant().account().requireActive();

            Instant now = clock.instant();
            Payment.CaptureResult result = payment.capture(request.amountMinor(), request.isFinal(), now);
            UUID journal = post(
                    actor,
                    payment,
                    LedgerTransactionType.PAYMENT_CAPTURE,
                    "Capture for payment to " + parties.merchant().name(),
                    PaymentAccounting.capture(
                            parties.customer().ledgerAccount(LedgerAccountPurpose.CUSTOMER_RESERVED),
                            parties.merchant().ledgerAccount(LedgerAccountPurpose.MERCHANT_AVAILABLE),
                            parties.customer().ledgerAccount(LedgerAccountPurpose.CUSTOMER_AVAILABLE),
                            result.capturedMinor(),
                            result.releasedMinor()));
            String note = result.releasedMinor() > 0
                    ? "Final capture released " + Money.format(result.releasedMinor())
                    : null;
            events.save(new PaymentEvent(
                    payment, PaymentEvent.Type.CAPTURED, result.capturedMinor(), result.fromStatus(), journal,
                    actor.userId(), note, now));
            Map<String, Object> details = new HashMap<>();
            details.put("amountMinor", result.capturedMinor());
            details.put("releasedMinor", result.releasedMinor());
            details.put("toStatus", payment.getStatus());
            audit.record(actor, AuditAction.PAYMENT_CAPTURED, "PAYMENT", paymentId, details);
            metrics.paymentOperation("capture", "success");
            return IdempotentResponse.created(PaymentResponse.from(payment), paymentId);
        });
    }

    @Transactional
    public IdempotentResult<PaymentResponse> voidPayment(
            CurrentActor actor, UUID paymentId, VoidPaymentRequest request, String idempotencyKey) {
        VoidPaymentRequest body = request == null ? new VoidPaymentRequest(null) : request;
        var idempotent = IdempotentRequest.of(actor.organizationId(), "PAYMENT_VOID", idempotencyKey, paymentId, body);
        return idempotency.execute(idempotent, PaymentResponse.class, () -> {
            Payment payment = lock(actor.organizationId(), paymentId);
            Parties parties = parties(payment);
            Instant now = clock.instant();
            PaymentStatus from = payment.getStatus();
            long released = payment.voidAuthorization(now);
            UUID journal = post(
                    actor,
                    payment,
                    LedgerTransactionType.PAYMENT_VOID,
                    "Void of authorization for payment to " + parties.merchant().name(),
                    PaymentAccounting.voidAuthorization(
                            parties.customer().ledgerAccount(LedgerAccountPurpose.CUSTOMER_RESERVED),
                            parties.customer().ledgerAccount(LedgerAccountPurpose.CUSTOMER_AVAILABLE),
                            released));
            events.save(new PaymentEvent(
                    payment, PaymentEvent.Type.VOIDED, released, from, journal, actor.userId(), body.reason(), now));
            audit.record(actor, AuditAction.PAYMENT_VOIDED, "PAYMENT", paymentId, Map.of("releasedMinor", released));
            metrics.paymentOperation("void", "success");
            return IdempotentResponse.ok(PaymentResponse.from(payment), paymentId);
        });
    }

    @Transactional
    public IdempotentResult<RefundResult> refund(
            CurrentActor actor, UUID paymentId, RefundPaymentRequest request, String idempotencyKey) {
        var idempotent =
                IdempotentRequest.of(actor.organizationId(), "PAYMENT_REFUND", idempotencyKey, paymentId, request);
        return idempotency.execute(idempotent, RefundResult.class, () -> {
            Payment payment = lock(actor.organizationId(), paymentId);
            Parties parties = parties(payment);
            parties.customer().account().requireActive();
            parties.merchant().account().requireActive();

            Instant now = clock.instant();
            PaymentStatus from = payment.refund(request.amountMinor(), now);
            UUID refundId = UUID.randomUUID();
            UUID journal = post(
                    actor,
                    payment,
                    LedgerTransactionType.PAYMENT_REFUND,
                    "Refund from " + parties.merchant().name() + " to " + parties.customer().name(),
                    PaymentAccounting.refund(
                            parties.merchant().ledgerAccount(LedgerAccountPurpose.MERCHANT_AVAILABLE),
                            parties.customer().ledgerAccount(LedgerAccountPurpose.CUSTOMER_AVAILABLE),
                            request.amountMinor()));
            Refund refund = refunds.save(new Refund(
                    refundId, payment, request.amountMinor(), request.reason(), journal, actor.userId(), now));
            events.save(new PaymentEvent(
                    payment, PaymentEvent.Type.REFUNDED, request.amountMinor(), from, journal, actor.userId(),
                    request.reason(), now));
            audit.record(
                    actor,
                    AuditAction.PAYMENT_REFUNDED,
                    "PAYMENT",
                    paymentId,
                    Map.of("refundId", refundId, "amountMinor", request.amountMinor()));
            metrics.paymentOperation("refund", "success");
            return IdempotentResponse.created(
                    new RefundResult(RefundResponse.from(refund), PaymentResponse.from(payment)), refundId);
        });
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID organizationId, UUID paymentId) {
        return PaymentResponse.from(find(organizationId, paymentId));
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> search(UUID organizationId, PaymentFilter filter, Pageable pageable) {
        return PageResponse.of(
                payments.findAll(PaymentSpecifications.matching(organizationId, filter), pageable),
                PaymentResponse::from);
    }

    @Transactional(readOnly = true)
    public List<PaymentEventResponse> timeline(UUID organizationId, UUID paymentId) {
        find(organizationId, paymentId);
        return events.timeline(organizationId, paymentId).stream()
                .map(PaymentEventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> refundsOf(UUID organizationId, UUID paymentId) {
        find(organizationId, paymentId);
        return refunds.forPayment(organizationId, paymentId).stream()
                .map(RefundResponse::from)
                .toList();
    }

    /** Locks a payment for a mutation in the caller's transaction (used by the dispute module). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment lock(UUID organizationId, UUID paymentId) {
        return payments.lockByIdAndOrganizationId(paymentId, organizationId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
    }

    /** Appends a timeline event in the caller's transaction (used by the dispute module). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendEvent(PaymentEvent event) {
        events.save(event);
    }

    public Parties parties(Payment payment) {
        return new Parties(
                accounts.handle(payment.getOrganizationId(), payment.getCustomerAccountId()),
                accounts.handle(payment.getOrganizationId(), payment.getMerchantAccountId()));
    }

    private Payment find(UUID organizationId, UUID paymentId) {
        return payments.findByIdAndOrganizationId(paymentId, organizationId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
    }

    private UUID post(
            CurrentActor actor,
            Payment payment,
            LedgerTransactionType type,
            String description,
            List<PostingLine> lines) {
        return ledger.post(new PostingRequest(
                actor.organizationId(), type, description, "PAYMENT", payment.getId(), actor.userId(), lines));
    }

    public record Parties(AccountHandle customer, AccountHandle merchant) {}
}
