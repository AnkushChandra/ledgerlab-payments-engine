package com.ledgerlab.dispute.api;

import com.ledgerlab.dispute.Dispute;
import com.ledgerlab.dispute.DisputeService;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Roles;
import com.ledgerlab.shared.web.PageRequests;
import com.ledgerlab.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@RequestMapping("/api/v1")
@Tag(name = "Disputes")
public class DisputeController {

    private static final String KEY_DESCRIPTION = "Unique key per logical operation; retries must reuse it";

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @PostMapping("/payments/{paymentId}/disputes")
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(summary = "Open a dispute and move the disputed funds into the merchant's dispute hold")
    public ResponseEntity<DisputeResponse> open(
            CurrentActor actor,
            @PathVariable UUID paymentId,
            @Parameter(description = KEY_DESCRIPTION) @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody OpenDisputeRequest request) {
        return disputeService.open(actor, paymentId, request, idempotencyKey).toResponseEntity();
    }

    @GetMapping("/payments/{paymentId}/disputes")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Disputes for a payment")
    public List<DisputeResponse> forPayment(CurrentActor actor, @PathVariable UUID paymentId) {
        return disputeService.forPayment(actor.organizationId(), paymentId);
    }

    @GetMapping("/disputes")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Dispute queue")
    public PageResponse<DisputeResponse> list(
            CurrentActor actor,
            @RequestParam(required = false) Dispute.Status status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var pageable = PageRequests.of(
                page, size, sort, Map.of("openedAt", "openedAt", "amountMinor", "amountMinor"), "openedAt,desc");
        return disputeService.search(actor.organizationId(), status, pageable);
    }

    @GetMapping("/disputes/{disputeId}")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Get a dispute with its payment")
    public DisputeResponse get(CurrentActor actor, @PathVariable UUID disputeId) {
        return disputeService.get(actor.organizationId(), disputeId);
    }

    @PostMapping("/disputes/{disputeId}/resolution")
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(summary = "Resolve a dispute as WON (merchant keeps funds) or LOST (chargeback to customer)")
    public ResponseEntity<DisputeResponse> resolve(
            CurrentActor actor,
            @PathVariable UUID disputeId,
            @Parameter(description = KEY_DESCRIPTION) @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody ResolveDisputeRequest request) {
        return disputeService.resolve(actor, disputeId, request, idempotencyKey).toResponseEntity();
    }
}
