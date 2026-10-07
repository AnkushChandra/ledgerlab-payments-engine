package com.ledgerlab.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.reconciliation.ReconciliationEngine.InternalPayment;
import com.ledgerlab.reconciliation.ReconciliationEngine.Result;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReconciliationEngineTest {

    private static final Instant T = Instant.parse("2026-09-01T10:00:00Z");

    private final InternalPayment captured = new InternalPayment(UUID.randomUUID(), "CAPTURED", 12_000, T);
    private final InternalPayment partlyRefunded =
            new InternalPayment(UUID.randomUUID(), "PARTIALLY_REFUNDED", 20_000, T.plusSeconds(60));
    private final InternalPayment refunded = new InternalPayment(UUID.randomUUID(), "REFUNDED", 0, T.plusSeconds(120));
    private final InternalPayment unreported = new InternalPayment(UUID.randomUUID(), "CAPTURED", 9_900, T.plusSeconds(5));
    private final List<InternalPayment> all = List.of(captured, partlyRefunded, refunded, unreported);
    private final Map<UUID, InternalPayment> byId =
            all.stream().collect(Collectors.toMap(InternalPayment::id, Function.identity()));

    @Test
    void classifiesEveryCategory() {
        UUID unknown = UUID.randomUUID();
        List<SettlementRow> rows = List.of(
                row(2, captured.id(), "CAPTURED", 12_000),
                row(3, partlyRefunded.id(), "PARTIALLY_REFUNDED", 25_000),
                row(4, refunded.id(), "CAPTURED", 40_000),
                row(5, unknown, "CAPTURED", 1_000),
                row(6, captured.id(), "CAPTURED", 12_000));

        List<Result> results = ReconciliationEngine.reconcile(rows, byId, all);

        assertThat(results).extracting(Result::classification).containsExactly(
                Classification.MATCHED,
                Classification.AMOUNT_MISMATCH,
                Classification.STATUS_MISMATCH,
                Classification.MISSING_INTERNAL,
                Classification.DUPLICATE_EXTERNAL,
                Classification.MISSING_EXTERNAL);
        assertThat(results.get(1).expectedAmountMinor()).isEqualTo(20_000);
        assertThat(results.get(1).actualAmountMinor()).isEqualTo(25_000);
        assertThat(results.get(2).message()).contains("CAPTURED").contains("REFUNDED");
        assertThat(results.get(4).message()).contains("row 2");
        assertThat(results.get(5).paymentId()).isEqualTo(unreported.id());
        assertThat(results.get(5).row()).isNull();
    }

    @Test
    void perfectFileIsFullyMatched() {
        List<SettlementRow> rows = all.stream()
                .map(p -> row(2, p.id(), p.status(), p.netSettledMinor()))
                .toList();
        assertThat(ReconciliationEngine.reconcile(rows, byId, all))
                .extracting(Result::classification)
                .containsOnly(Classification.MATCHED)
                .hasSize(4);
    }

    @Test
    void statusMismatchTakesPriorityOverAmountMismatch() {
        List<Result> results = ReconciliationEngine.reconcile(
                List.of(row(2, refunded.id(), "CAPTURED", 999)), byId, List.of());
        assertThat(results).singleElement().extracting(Result::classification)
                .isEqualTo(Classification.STATUS_MISMATCH);
    }

    @Test
    void missingExternalIsOrderedByCaptureTime() {
        List<Result> results = ReconciliationEngine.reconcile(List.of(), byId, all);
        assertThat(results).extracting(Result::paymentId)
                .containsExactly(captured.id(), unreported.id(), partlyRefunded.id(), refunded.id());
    }

    private static SettlementRow row(int line, UUID paymentId, String status, long amount) {
        return new SettlementRow(line, "rec-" + line + "-" + UUID.randomUUID(), paymentId, status, amount, "USD", T);
    }
}
