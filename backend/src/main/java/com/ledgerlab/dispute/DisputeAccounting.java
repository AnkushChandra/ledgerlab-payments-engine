package com.ledgerlab.dispute;

import com.ledgerlab.ledger.PostingLine;
import java.util.List;
import java.util.UUID;

/** Pure posting rules for the dispute policy documented in docs/domain-model.md. */
public final class DisputeAccounting {

    private DisputeAccounting() {}

    /** Freeze disputed merchant funds: Dr merchant available, Cr merchant dispute hold. */
    public static List<PostingLine> opened(UUID merchantAvailable, UUID merchantHold, long amount) {
        return List.of(PostingLine.debit(merchantAvailable, amount), PostingLine.credit(merchantHold, amount));
    }

    /** Merchant wins: Dr merchant dispute hold, Cr merchant available. */
    public static List<PostingLine> won(UUID merchantHold, UUID merchantAvailable, long amount) {
        return List.of(PostingLine.debit(merchantHold, amount), PostingLine.credit(merchantAvailable, amount));
    }

    /** Merchant loses (chargeback): Dr merchant dispute hold, Cr customer available. */
    public static List<PostingLine> lost(UUID merchantHold, UUID customerAvailable, long amount) {
        return List.of(PostingLine.debit(merchantHold, amount), PostingLine.credit(customerAvailable, amount));
    }
}
