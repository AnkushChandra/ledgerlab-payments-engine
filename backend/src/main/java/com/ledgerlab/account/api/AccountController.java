package com.ledgerlab.account.api;

import com.ledgerlab.account.AccountService;
import com.ledgerlab.account.FinancialAccount;
import com.ledgerlab.ledger.LedgerEntryView;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Roles;
import com.ledgerlab.shared.web.PageRequests;
import com.ledgerlab.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "name", "name");

    private final AccountService accountService;
    private final LedgerService ledgerService;

    public AccountController(AccountService accountService, LedgerService ledgerService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    @GetMapping
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "List financial accounts with balances")
    public PageResponse<AccountResponse> list(
            CurrentActor actor,
            @RequestParam(required = false) FinancialAccount.Type type,
            @RequestParam(required = false) FinancialAccount.Status status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var pageable = PageRequests.of(page, size, sort, SORTS, "createdAt,desc");
        return accountService.search(actor.organizationId(), type, status, q, pageable);
    }

    @PostMapping
    @PreAuthorize(Roles.OPERATIONS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer or merchant account and its ledger accounts")
    public AccountResponse create(CurrentActor actor, @Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(actor, request);
    }

    @GetMapping("/{accountId}")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Get an account with balances derived from the ledger")
    public AccountResponse get(CurrentActor actor, @PathVariable UUID accountId) {
        return accountService.get(actor.organizationId(), accountId);
    }

    @PatchMapping("/{accountId}")
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Freeze, unfreeze or close an account (closing requires zero balances)")
    public AccountResponse changeStatus(
            CurrentActor actor, @PathVariable UUID accountId, @Valid @RequestBody ChangeStatusRequest request) {
        return accountService.changeStatus(actor, accountId, request.status());
    }

    @GetMapping("/{accountId}/ledger-entries")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Ledger entries for the account, newest first, with running balances")
    public PageResponse<LedgerEntryView> entries(
            CurrentActor actor,
            @PathVariable UUID accountId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        accountService.get(actor.organizationId(), accountId);
        var pageable = PageRequests.of(page, size, null, Map.of("createdAt", "createdAt"), "createdAt,desc");
        var result = ledgerService.entriesOfFinancialAccount(
                actor.organizationId(), accountId, pageable.getPageNumber(), pageable.getPageSize());
        int totalPages = (int) ((result.totalItems() + pageable.getPageSize() - 1) / pageable.getPageSize());
        return new PageResponse<>(
                result.items(), pageable.getPageNumber(), pageable.getPageSize(), result.totalItems(), totalPages);
    }

    public record ChangeStatusRequest(@NotNull FinancialAccount.Status status) {}
}
