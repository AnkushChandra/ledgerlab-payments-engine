package com.ledgerlab.funds.api;

import com.ledgerlab.funds.Deposit;
import java.time.Instant;
import java.util.UUID;

public record DepositResponse(
        UUID id,
        UUID accountId,
        long amountMinor,
        String currency,
        String memo,
        UUID ledgerTransactionId,
        UUID createdBy,
        Instant createdAt) {

    public static DepositResponse from(Deposit deposit) {
        return new DepositResponse(
                deposit.getId(),
                deposit.getAccountId(),
                deposit.getAmountMinor(),
                deposit.getCurrency(),
                deposit.getMemo(),
                deposit.getLedgerTransactionId(),
                deposit.getCreatedBy(),
                deposit.getCreatedAt());
    }
}
