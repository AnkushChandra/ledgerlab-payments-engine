package com.ledgerlab.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransactionView(Header transaction, List<Entry> entries) {

    public record Header(
            UUID id,
            LedgerTransactionType type,
            String currency,
            String description,
            String sourceType,
            UUID sourceId,
            Instant postedAt,
            UUID createdBy) {}

    public record Entry(
            UUID id,
            UUID ledgerAccountId,
            LedgerAccountPurpose purpose,
            UUID financialAccountId,
            long amountMinor,
            String direction) {}
}
