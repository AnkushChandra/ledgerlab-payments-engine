package com.ledgerlab.reconciliation;

import com.ledgerlab.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure matching logic (no I/O). Rules, in priority order per row: duplicate of an earlier row,
 * unknown payment, status mismatch, amount mismatch, otherwise matched. Afterwards, every internal
 * payment captured in the period but absent from the file is reported as missing externally.
 */
public final class ReconciliationEngine {

    private ReconciliationEngine() {}

    /** The internal facts needed to reconcile one payment. */
    public record InternalPayment(UUID id, String status, long netSettledMinor, Instant firstCapturedAt) {}

    public record Result(
            Classification classification,
            SettlementRow row,
            UUID paymentId,
            Long expectedAmountMinor,
            Long actualAmountMinor,
            String expectedStatus,
            String actualStatus,
            String message) {}

    public static List<Result> reconcile(
            List<SettlementRow> rows,
            Map<UUID, InternalPayment> internalById,
            List<InternalPayment> capturedInPeriod) {
        List<Result> results = new ArrayList<>(rows.size());
        Map<UUID, Integer> firstRowByPayment = new HashMap<>();
        Set<UUID> referenced = new HashSet<>();

        for (SettlementRow row : rows) {
            referenced.add(row.paymentId());
            Integer earlier = firstRowByPayment.putIfAbsent(row.paymentId(), row.rowNumber());
            InternalPayment internal = internalById.get(row.paymentId());
            Long expectedAmount = internal == null ? null : internal.netSettledMinor();
            String expectedStatus = internal == null ? null : internal.status();

            Classification classification;
            String message;
            if (earlier != null) {
                classification = Classification.DUPLICATE_EXTERNAL;
                message = "Payment was already reported on row " + earlier;
            } else if (internal == null) {
                classification = Classification.MISSING_INTERNAL;
                message = "No payment with this id exists in LedgerLab";
            } else if (!internal.status().equals(row.status())) {
                classification = Classification.STATUS_MISMATCH;
                message = "Processor reports " + row.status() + " but LedgerLab has " + internal.status();
            } else if (internal.netSettledMinor() != row.amountMinor()) {
                classification = Classification.AMOUNT_MISMATCH;
                message = "Processor reports " + Money.format(row.amountMinor()) + " but LedgerLab expects "
                        + Money.format(internal.netSettledMinor());
            } else {
                classification = Classification.MATCHED;
                message = "Status and amount match";
            }
            results.add(new Result(
                    classification,
                    row,
                    row.paymentId(),
                    expectedAmount,
                    row.amountMinor(),
                    expectedStatus,
                    row.status(),
                    message));
        }

        capturedInPeriod.stream()
                .filter(p -> !referenced.contains(p.id()))
                .sorted(Comparator.comparing(InternalPayment::firstCapturedAt).thenComparing(InternalPayment::id))
                .forEach(p -> results.add(new Result(
                        Classification.MISSING_EXTERNAL,
                        null,
                        p.id(),
                        p.netSettledMinor(),
                        null,
                        p.status(),
                        null,
                        "Captured in the settlement period but absent from the processor file")));
        return results;
    }
}
