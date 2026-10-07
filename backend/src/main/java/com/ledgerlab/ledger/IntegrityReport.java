package com.ledgerlab.ledger;

import java.time.Instant;

public record IntegrityReport(
        boolean healthy,
        long transactionCount,
        long entryCount,
        long unbalancedTransactions,
        long accountsWithBalanceDrift,
        long clearingBalanceMinor,
        long totalLiabilitiesMinor,
        boolean accountingEquationHolds,
        Instant checkedAt) {}
