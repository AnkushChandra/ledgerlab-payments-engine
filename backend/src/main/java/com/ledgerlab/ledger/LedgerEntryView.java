package com.ledgerlab.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * A ledger entry as shown in an account statement.
 *
 * @param amountMinor raw signed amount (debit positive)
 * @param effectMinor effect on the account's presented balance
 * @param balanceAfterMinor presented balance of the ledger account after this entry
 */
public record LedgerEntryView(
        UUID id,
        UUID ledgerTransactionId,
        LedgerTransactionType transactionType,
        String description,
        String sourceType,
        UUID sourceId,
        UUID ledgerAccountId,
        LedgerAccountPurpose purpose,
        long amountMinor,
        String direction,
        long effectMinor,
        long balanceAfterMinor,
        Instant createdAt) {}
