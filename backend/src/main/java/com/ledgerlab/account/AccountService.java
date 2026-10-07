package com.ledgerlab.account;

import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.account.api.CreateAccountRequest;
import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditService;
import com.ledgerlab.ledger.LedgerAccountPurpose;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.web.PageResponse;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final FinancialAccountRepository accounts;
    private final LedgerService ledger;
    private final AuditService audit;
    private final Clock clock;

    public AccountService(FinancialAccountRepository accounts, LedgerService ledger, AuditService audit, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AccountResponse create(CurrentActor actor, CreateAccountRequest request) {
        String reference = request.reference().trim();
        if (accounts.existsByOrganizationIdAndReference(actor.organizationId(), reference)) {
            throw new ApiException(ErrorCode.DUPLICATE_REFERENCE, "An account with reference '" + reference
                    + "' already exists.");
        }
        FinancialAccount account = new FinancialAccount(
                UUID.randomUUID(),
                actor.organizationId(),
                request.type(),
                request.name().trim(),
                reference,
                actor.userId(),
                clock.instant());
        // Flush so the ledger rows inserted over JDBC can reference this account.
        accounts.saveAndFlush(account);
        ledger.createAccounts(actor.organizationId(), account.getId(), request.type().ledgerPurposes());
        audit.record(
                actor,
                AuditAction.ACCOUNT_CREATED,
                "ACCOUNT",
                account.getId(),
                Map.of("type", account.getType(), "reference", reference));
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID organizationId, UUID accountId) {
        return toResponse(find(organizationId, accountId));
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> search(
            UUID organizationId,
            FinancialAccount.Type type,
            FinancialAccount.Status status,
            String q,
            Pageable pageable) {
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        Page<FinancialAccount> page = accounts.search(organizationId, type, status, pattern, pageable);
        var balances = ledger.balancesOf(
                organizationId, page.getContent().stream().map(FinancialAccount::getId).toList());
        return PageResponse.of(page, a -> AccountResponse.from(a, balances.getOrDefault(a.getId(), Map.of())));
    }

    @Transactional
    public AccountResponse changeStatus(CurrentActor actor, UUID accountId, FinancialAccount.Status target) {
        FinancialAccount account = accounts
                .lockByIdAndOrganizationId(accountId, actor.organizationId())
                .orElseThrow(() -> ApiException.notFound("Account"));
        FinancialAccount.Status previous = account.getStatus();
        if (target == FinancialAccount.Status.CLOSED && previous != FinancialAccount.Status.CLOSED) {
            var ledgerIds = ledger.accountsOf(actor.organizationId(), accountId);
            var locked = ledger.lockAccounts(actor.organizationId(), ledgerIds.values().stream().toList());
            boolean hasBalance = locked.values().stream().anyMatch(l -> l.balanceMinor() != 0);
            if (hasBalance) {
                throw new ApiException(
                        ErrorCode.ACCOUNT_HAS_BALANCE, "Only accounts with zero balances can be closed.");
            }
        }
        account.changeStatus(target);
        if (previous != target) {
            audit.record(
                    actor,
                    AuditAction.ACCOUNT_STATUS_CHANGED,
                    "ACCOUNT",
                    account.getId(),
                    Map.of("fromStatus", previous, "toStatus", target));
        }
        return toResponse(account);
    }

    /**
     * Loads an account and its ledger account ids for use by a money-movement operation in the
     * caller's transaction. Visibility is always restricted to the caller's organization.
     */
    @Transactional(readOnly = true)
    public AccountHandle handle(UUID organizationId, UUID accountId) {
        FinancialAccount account = find(organizationId, accountId);
        return new AccountHandle(account, ledger.accountsOf(organizationId, accountId));
    }

    private FinancialAccount find(UUID organizationId, UUID accountId) {
        return accounts.findByIdAndOrganizationId(accountId, organizationId)
                .orElseThrow(() -> ApiException.notFound("Account"));
    }

    private AccountResponse toResponse(FinancialAccount account) {
        var balances = ledger.balancesOf(account.getOrganizationId(), java.util.List.of(account.getId()));
        return AccountResponse.from(account, balances.getOrDefault(account.getId(), Map.of()));
    }

    /** A financial account plus the ids of the ledger accounts that back it. */
    public record AccountHandle(FinancialAccount account, Map<LedgerAccountPurpose, UUID> ledgerAccounts) {

        public UUID id() {
            return account.getId();
        }

        public String name() {
            return account.getName();
        }

        public UUID available() {
            return ledgerAccounts.get(account.getType().availablePurpose());
        }

        public UUID ledgerAccount(LedgerAccountPurpose purpose) {
            UUID id = ledgerAccounts.get(purpose);
            if (id == null) {
                throw new IllegalStateException("Account " + account.getId() + " has no " + purpose + " ledger account");
            }
            return id;
        }
    }
}
