package com.ledgerlab.account.api;

import com.ledgerlab.account.FinancialAccount;
import com.ledgerlab.ledger.LedgerAccountPurpose;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * @param availableMinor spendable funds (customer available or merchant available)
 * @param heldMinor funds held by open authorizations (customers) or open disputes (merchants)
 */
public record AccountResponse(
        UUID id,
        FinancialAccount.Type type,
        String name,
        String reference,
        FinancialAccount.Status status,
        String currency,
        long availableMinor,
        long heldMinor,
        Map<LedgerAccountPurpose, Long> ledgerBalances,
        Instant createdAt) {

    public static AccountResponse from(FinancialAccount account, Map<LedgerAccountPurpose, Long> balances) {
        var purposes = account.getType().ledgerPurposes();
        return new AccountResponse(
                account.getId(),
                account.getType(),
                account.getName(),
                account.getReference(),
                account.getStatus(),
                "USD",
                balances.getOrDefault(purposes.get(0), 0L),
                balances.getOrDefault(purposes.get(1), 0L),
                Map.copyOf(balances),
                account.getCreatedAt());
    }
}
