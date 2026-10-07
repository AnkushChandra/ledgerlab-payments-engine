package com.ledgerlab.ledger;

/** Chart of accounts. Each purpose fixes the account type, normal side and overdraft policy. */
public enum LedgerAccountPurpose {
    EXTERNAL_CLEARING(AccountType.ASSET, true),
    CUSTOMER_AVAILABLE(AccountType.LIABILITY, false),
    CUSTOMER_RESERVED(AccountType.LIABILITY, false),
    MERCHANT_AVAILABLE(AccountType.LIABILITY, false),
    MERCHANT_DISPUTE_HOLD(AccountType.LIABILITY, false);

    public enum AccountType {
        ASSET,
        LIABILITY
    }

    public enum NormalSide {
        DEBIT,
        CREDIT
    }

    private final AccountType accountType;
    private final boolean allowNegative;

    LedgerAccountPurpose(AccountType accountType, boolean allowNegative) {
        this.accountType = accountType;
        this.allowNegative = allowNegative;
    }

    public AccountType accountType() {
        return accountType;
    }

    public NormalSide normalSide() {
        return accountType == AccountType.ASSET ? NormalSide.DEBIT : NormalSide.CREDIT;
    }

    public boolean allowNegative() {
        return allowNegative;
    }

    /**
     * Converts a raw debit-positive balance into the balance a user expects to see: positive means
     * "more of what this account normally holds".
     */
    public long presented(long rawBalance) {
        return normalSide() == NormalSide.DEBIT ? rawBalance : -rawBalance;
    }

    public boolean isMerchantAccount() {
        return this == MERCHANT_AVAILABLE || this == MERCHANT_DISPUTE_HOLD;
    }
}
