package com.ledgerlab.funds;

import com.ledgerlab.account.AccountService;
import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditService;
import com.ledgerlab.funds.api.DepositRequest;
import com.ledgerlab.funds.api.DepositResponse;
import com.ledgerlab.funds.api.TransferRequest;
import com.ledgerlab.funds.api.TransferResponse;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.ledger.LedgerTransactionType;
import com.ledgerlab.ledger.PostingLine;
import com.ledgerlab.ledger.PostingRequest;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.idempotency.IdempotencyService;
import com.ledgerlab.shared.idempotency.IdempotentRequest;
import com.ledgerlab.shared.idempotency.IdempotentResponse;
import com.ledgerlab.shared.idempotency.IdempotentResult;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.web.PageResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FundsService {

    private final AccountService accounts;
    private final LedgerService ledger;
    private final DepositRepository deposits;
    private final TransferRepository transfers;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;

    public FundsService(
            AccountService accounts,
            LedgerService ledger,
            DepositRepository deposits,
            TransferRepository transfers,
            IdempotencyService idempotency,
            AuditService audit,
            Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.deposits = deposits;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IdempotentResult<DepositResponse> deposit(CurrentActor actor, DepositRequest request, String idempotencyKey) {
        var idempotent = IdempotentRequest.of(actor.organizationId(), "DEPOSIT", idempotencyKey, request);
        return idempotency.execute(idempotent, DepositResponse.class, () -> {
            var account = accounts.handle(actor.organizationId(), request.accountId());
            account.account().requireActive();
            UUID depositId = UUID.randomUUID();
            UUID clearing = ledger.clearingAccountId(actor.organizationId());
            UUID ledgerTransactionId = ledger.post(new PostingRequest(
                    actor.organizationId(),
                    LedgerTransactionType.SIMULATED_DEPOSIT,
                    "Simulated bank deposit to " + account.name(),
                    "DEPOSIT",
                    depositId,
                    actor.userId(),
                    List.of(
                            PostingLine.debit(clearing, request.amountMinor()),
                            PostingLine.credit(account.available(), request.amountMinor()))));
            Deposit deposit = deposits.save(new Deposit(
                    depositId,
                    actor.organizationId(),
                    account.id(),
                    request.amountMinor(),
                    blankToNull(request.memo()),
                    ledgerTransactionId,
                    actor.userId(),
                    clock.instant()));
            audit.record(
                    actor,
                    AuditAction.DEPOSIT_CREATED,
                    "DEPOSIT",
                    depositId,
                    Map.of("accountId", account.id(), "amountMinor", request.amountMinor()));
            return IdempotentResponse.created(DepositResponse.from(deposit), depositId);
        });
    }

    @Transactional
    public IdempotentResult<TransferResponse> transfer(
            CurrentActor actor, TransferRequest request, String idempotencyKey) {
        var idempotent = IdempotentRequest.of(actor.organizationId(), "TRANSFER", idempotencyKey, request);
        return idempotency.execute(idempotent, TransferResponse.class, () -> {
            if (request.sourceAccountId().equals(request.destinationAccountId())) {
                throw new ApiException(ErrorCode.SAME_ACCOUNT_TRANSFER, "Source and destination accounts must differ.");
            }
            var source = accounts.handle(actor.organizationId(), request.sourceAccountId());
            var destination = accounts.handle(actor.organizationId(), request.destinationAccountId());
            source.account().requireActive();
            destination.account().requireActive();
            UUID transferId = UUID.randomUUID();
            UUID ledgerTransactionId = ledger.post(new PostingRequest(
                    actor.organizationId(),
                    LedgerTransactionType.TRANSFER,
                    "Transfer from " + source.name() + " to " + destination.name(),
                    "TRANSFER",
                    transferId,
                    actor.userId(),
                    List.of(
                            PostingLine.debit(source.available(), request.amountMinor()),
                            PostingLine.credit(destination.available(), request.amountMinor()))));
            Transfer transfer = transfers.save(new Transfer(
                    transferId,
                    actor.organizationId(),
                    source.id(),
                    destination.id(),
                    request.amountMinor(),
                    blankToNull(request.memo()),
                    ledgerTransactionId,
                    actor.userId(),
                    clock.instant()));
            audit.record(
                    actor,
                    AuditAction.TRANSFER_CREATED,
                    "TRANSFER",
                    transferId,
                    Map.of(
                            "sourceAccountId", source.id(),
                            "destinationAccountId", destination.id(),
                            "amountMinor", request.amountMinor()));
            return IdempotentResponse.created(TransferResponse.from(transfer), transferId);
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<DepositResponse> listDeposits(UUID organizationId, UUID accountId, Pageable pageable) {
        return PageResponse.of(deposits.search(organizationId, accountId, pageable), DepositResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> listTransfers(UUID organizationId, UUID accountId, Pageable pageable) {
        return PageResponse.of(transfers.search(organizationId, accountId, pageable), TransferResponse::from);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
