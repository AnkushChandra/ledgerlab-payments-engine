package com.ledgerlab.payment.api;

import com.ledgerlab.payment.PaymentFilter;
import com.ledgerlab.payment.PaymentService;
import com.ledgerlab.payment.PaymentStatus;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Roles;
import com.ledgerlab.shared.web.PageRequests;
import com.ledgerlab.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    private static final Map<String, String> SORTS = Map.of(
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "authorizedAmountMinor", "authorizedAmountMinor");
    private static final String KEY_DESCRIPTION = "Unique key per logical operation; retries must reuse it";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(
            summary = "Authorize a payment",
            description = "Reserves customer funds. Returns 201 with status AUTHORIZED, or FAILED when the customer "
                    + "has insufficient funds or an inactive account.")
    public ResponseEntity<PaymentResponse> authorize(
            CurrentActor actor,
            @Parameter(description = KEY_DESCRIPTION) @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody AuthorizePaymentRequest request) {
        return paymentService.authorize(actor, request, idempotencyKey).toResponseEntity();
    }

    @GetMapping
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "List payments with filters")
    public PageResponse<PaymentResponse> list(
            CurrentActor actor,
            @RequestParam(required = false) List<PaymentStatus> status,
            @RequestParam(required = false) UUID customerAccountId,
            @RequestParam(required = false) UUID merchantAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @Parameter(description = "Payment id or part of the reference") @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var filter = new PaymentFilter(status, customerAccountId, merchantAccountId, createdFrom, createdTo, q);
        return paymentService.search(
                actor.organizationId(), filter, PageRequests.of(page, size, sort, SORTS, "createdAt,desc"));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Get a payment, its amounts and currently allowed actions")
    public PaymentResponse get(CurrentActor actor, @PathVariable UUID paymentId) {
        return paymentService.get(actor.organizationId(), paymentId);
    }

    @GetMapping("/{paymentId}/events")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Immutable payment timeline, oldest first")
    public List<PaymentEventResponse> events(CurrentActor actor, @PathVariable UUID paymentId) {
        return paymentService.timeline(actor.organizationId(), paymentId);
    }

    @PostMapping("/{paymentId}/captures")
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(summary = "Capture all or part of the remaining authorization")
    public ResponseEntity<PaymentResponse> capture(
            CurrentActor actor,
            @PathVariable UUID paymentId,
            @Parameter(description = KEY_DESCRIPTION) @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody CapturePaymentRequest request) {
        return paymentService.capture(actor, paymentId, request, idempotencyKey).toResponseEntity();
    }

    @PostMapping("/{paymentId}/void")
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(summary = "Void an uncaptured authorization and release the reserved funds")
    public ResponseEntity<PaymentResponse> voidPayment(
            CurrentActor actor,
            @PathVariable UUID paymentId,
            @Parameter(description = KEY_DESCRIPTION) @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody(required = false) VoidPaymentRequest request) {
        return paymentService.voidPayment(actor, paymentId, request, idempotencyKey).toResponseEntity();
    }

    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(summary = "Refund captured funds to the customer")
    public ResponseEntity<RefundResult> refund(
            CurrentActor actor,
            @PathVariable UUID paymentId,
            @Parameter(description = KEY_DESCRIPTION) @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody RefundPaymentRequest request) {
        return paymentService.refund(actor, paymentId, request, idempotencyKey).toResponseEntity();
    }

    @GetMapping("/{paymentId}/refunds")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Refunds issued for a payment")
    public List<RefundResponse> refunds(CurrentActor actor, @PathVariable UUID paymentId) {
        return paymentService.refundsOf(actor.organizationId(), paymentId);
    }
}
