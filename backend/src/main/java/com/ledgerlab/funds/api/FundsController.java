package com.ledgerlab.funds.api;

import com.ledgerlab.funds.FundsService;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Roles;
import com.ledgerlab.shared.web.PageRequests;
import com.ledgerlab.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Deposits and transfers")
public class FundsController {

    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "amountMinor", "amountMinor");

    private final FundsService fundsService;

    public FundsController(FundsService fundsService) {
        this.fundsService = fundsService;
    }

    @PostMapping("/deposits")
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(summary = "Simulate an external bank deposit into an account")
    public ResponseEntity<DepositResponse> deposit(
            CurrentActor actor,
            @Parameter(description = "Unique key per logical operation; retries must reuse it")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        return fundsService.deposit(actor, request, idempotencyKey).toResponseEntity();
    }

    @GetMapping("/deposits")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "List deposits")
    public PageResponse<DepositResponse> deposits(
            CurrentActor actor,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return fundsService.listDeposits(
                actor.organizationId(), accountId, PageRequests.of(page, size, sort, SORTS, "createdAt,desc"));
    }

    @PostMapping("/transfers")
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(summary = "Transfer available funds between two active accounts")
    public ResponseEntity<TransferResponse> transfer(
            CurrentActor actor,
            @Parameter(description = "Unique key per logical operation; retries must reuse it")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return fundsService.transfer(actor, request, idempotencyKey).toResponseEntity();
    }

    @GetMapping("/transfers")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "List transfers, optionally involving one account")
    public PageResponse<TransferResponse> transfers(
            CurrentActor actor,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return fundsService.listTransfers(
                actor.organizationId(), accountId, PageRequests.of(page, size, sort, SORTS, "createdAt,desc"));
    }
}
