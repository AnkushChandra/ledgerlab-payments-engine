package com.ledgerlab.payment;

import com.ledgerlab.ledger.PostingLine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure posting rules for payment operations (see docs/domain-model.md, "Posting rules"). Each
 * method returns balanced lines; {@link com.ledgerlab.ledger.PostingRequest} re-verifies balance.
 */
public final class PaymentAccounting {

    private PaymentAccounting() {}

    /** Reserve customer funds: Dr customer available, Cr customer reserved. */
    public static List<PostingLine> authorization(UUID customerAvailable, UUID customerReserved, long amount) {
        return List.of(PostingLine.debit(customerAvailable, amount), PostingLine.credit(customerReserved, amount));
    }

    /**
     * Pay the merchant from the reservation and, for a final capture, release any uncaptured
     * remainder back to the customer.
     */
    public static List<PostingLine> capture(
            UUID customerReserved, UUID merchantAvailable, UUID customerAvailable, long captured, long released) {
        List<PostingLine> lines = new ArrayList<>(4);
        lines.add(PostingLine.debit(customerReserved, captured));
        lines.add(PostingLine.credit(merchantAvailable, captured));
        if (released > 0) {
            lines.add(PostingLine.debit(customerReserved, released));
            lines.add(PostingLine.credit(customerAvailable, released));
        }
        return lines;
    }

    /** Release the whole reservation: Dr customer reserved, Cr customer available. */
    public static List<PostingLine> voidAuthorization(UUID customerReserved, UUID customerAvailable, long amount) {
        return List.of(PostingLine.debit(customerReserved, amount), PostingLine.credit(customerAvailable, amount));
    }

    /** Return captured funds: Dr merchant available, Cr customer available. */
    public static List<PostingLine> refund(UUID merchantAvailable, UUID customerAvailable, long amount) {
        return List.of(PostingLine.debit(merchantAvailable, amount), PostingLine.credit(customerAvailable, amount));
    }
}
