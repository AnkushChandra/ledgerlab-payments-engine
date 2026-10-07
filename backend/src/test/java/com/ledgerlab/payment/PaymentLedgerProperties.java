package com.ledgerlab.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.dispute.DisputeAccounting;
import com.ledgerlab.ledger.LedgerAccountPurpose;
import com.ledgerlab.ledger.LedgerTransactionType;
import com.ledgerlab.ledger.PostingLine;
import com.ledgerlab.ledger.PostingRequest;
import com.ledgerlab.shared.error.ApiException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/**
 * Property-based tests over the payment aggregate and its posting rules. An in-memory ledger
 * applies every journal the domain produces, and the invariants are checked after every step.
 */
class PaymentLedgerProperties {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    enum Kind {
        CAPTURE,
        FINAL_CAPTURE,
        VOID,
        REFUND,
        OPEN_DISPUTE,
        RESOLVE_WON,
        RESOLVE_LOST
    }

    record Op(Kind kind, long amount) {}

    @Provide
    Arbitrary<List<Op>> operations() {
        Arbitrary<Op> op = Combinators.combine(
                        Arbitraries.of(Kind.class), Arbitraries.longs().between(1, 120_000))
                .as(Op::new);
        return op.list().ofMinSize(1).ofMaxSize(25);
    }

    @Property(tries = 500)
    void arbitraryOperationSequencesKeepTheLedgerBalanced(
            @ForAll @LongRange(min = 1, max = 100_000) long authorized,
            @ForAll @LongRange(min = 0, max = 50_000) long extraFunds,
            @ForAll("operations") List<Op> ops) {
        Model model = new Model(authorized + extraFunds);
        Payment payment = model.authorize(authorized);
        model.assertInvariants(payment);

        for (Op op : ops) {
            Snapshot before = Snapshot.of(payment);
            try {
                model.apply(payment, op);
            } catch (ApiException rejected) {
                // A rejected operation must leave the aggregate untouched.
                assertThat(Snapshot.of(payment)).isEqualTo(before);
            }
            model.assertInvariants(payment);
        }
    }

    @Property(tries = 300)
    void repeatedPartialCapturesNeverExceedTheAuthorization(
            @ForAll @LongRange(min = 1, max = 100_000) long authorized,
            @ForAll @Size(min = 1, max = 30) List<@IntRange(min = 1, max = 40_000) Integer> captures) {
        Payment payment = PaymentTestData.authorized(authorized);
        long accepted = 0;
        for (int amount : captures) {
            long remaining = authorized - accepted;
            try {
                payment.capture(amount, false, NOW);
                accepted += amount;
                assertThat((long) amount).isLessThanOrEqualTo(remaining);
            } catch (ApiException e) {
                assertThat(remaining == 0 || amount > remaining).isTrue();
            }
            assertThat(payment.getCapturedAmountMinor()).isEqualTo(accepted).isLessThanOrEqualTo(authorized);
        }
        assertThat(payment.getStatus())
                .isEqualTo(accepted == authorized ? PaymentStatus.CAPTURED
                        : accepted == 0 ? PaymentStatus.AUTHORIZED : PaymentStatus.PARTIALLY_CAPTURED);
    }

    @Property(tries = 300)
    void repeatedRefundsNeverExceedCapturedFunds(
            @ForAll @LongRange(min = 1, max = 100_000) long authorized,
            @ForAll @Size(min = 1, max = 30) List<@IntRange(min = 1, max = 40_000) Integer> refunds) {
        Payment payment = PaymentTestData.authorized(authorized);
        payment.capture(authorized, false, NOW);
        long refunded = 0;
        for (int amount : refunds) {
            try {
                payment.refund(amount, NOW);
                refunded += amount;
            } catch (ApiException e) {
                assertThat(refunded + amount > authorized || refunded == authorized).isTrue();
            }
            assertThat(payment.getRefundedAmountMinor()).isEqualTo(refunded).isLessThanOrEqualTo(authorized);
        }
    }

    @Property(tries = 300)
    void postingRequestsAcceptExactlyTheBalancedJournals(
            @ForAll @Size(min = 2, max = 8) List<@LongRange(min = -1_000_000, max = 1_000_000) Long> amounts) {
        List<Long> nonZero = amounts.stream().map(a -> a == 0 ? 1L : a).toList();
        List<PostingLine> lines = nonZero.stream()
                .map(a -> new PostingLine(UUID.randomUUID(), a))
                .toList();
        long sum = nonZero.stream().mapToLong(Long::longValue).sum();
        if (sum == 0) {
            assertThat(request(lines).lines()).hasSize(lines.size());
        } else {
            assertThatThrownBy(() -> request(lines)).hasMessageContaining("unbalanced");
            // Adding the exact offsetting line always produces a valid journal.
            var balanced = new java.util.ArrayList<>(lines);
            balanced.add(new PostingLine(UUID.randomUUID(), -sum));
            assertThat(request(balanced).lines()).hasSize(lines.size() + 1);
        }
    }

    private static PostingRequest request(List<PostingLine> lines) {
        return new PostingRequest(
                UUID.randomUUID(), LedgerTransactionType.TRANSFER, "test", "TEST", UUID.randomUUID(), null, lines);
    }

    record Snapshot(PaymentStatus status, long captured, long released, long refunded, long disputed, long lost) {
        static Snapshot of(Payment p) {
            return new Snapshot(
                    p.getStatus(),
                    p.getCapturedAmountMinor(),
                    p.getReleasedAmountMinor(),
                    p.getRefundedAmountMinor(),
                    p.getDisputedAmountMinor(),
                    p.getDisputeLostAmountMinor());
        }
    }

    /** Minimal in-memory double-entry ledger with the same purposes as production. */
    static final class Model {
        final UUID clearing = UUID.randomUUID();
        final UUID customerAvailable = UUID.randomUUID();
        final UUID customerReserved = UUID.randomUUID();
        final UUID merchantAvailable = UUID.randomUUID();
        final UUID merchantHold = UUID.randomUUID();
        final Map<UUID, LedgerAccountPurpose> purposes = Map.of(
                clearing, LedgerAccountPurpose.EXTERNAL_CLEARING,
                customerAvailable, LedgerAccountPurpose.CUSTOMER_AVAILABLE,
                customerReserved, LedgerAccountPurpose.CUSTOMER_RESERVED,
                merchantAvailable, LedgerAccountPurpose.MERCHANT_AVAILABLE,
                merchantHold, LedgerAccountPurpose.MERCHANT_DISPUTE_HOLD);
        final Map<UUID, Long> raw = new HashMap<>();
        final long deposited;

        Model(long deposit) {
            this.deposited = deposit;
            post(List.of(PostingLine.debit(clearing, deposit), PostingLine.credit(customerAvailable, deposit)));
        }

        Payment authorize(long amount) {
            Payment payment = PaymentTestData.authorized(amount);
            post(PaymentAccounting.authorization(customerAvailable, customerReserved, amount));
            return payment;
        }

        void apply(Payment payment, Op op) {
            switch (op.kind()) {
                case CAPTURE, FINAL_CAPTURE -> {
                    var result = payment.capture(op.amount(), op.kind() == Kind.FINAL_CAPTURE, NOW);
                    post(PaymentAccounting.capture(
                            customerReserved, merchantAvailable, customerAvailable, result.capturedMinor(),
                            result.releasedMinor()));
                }
                case VOID -> post(PaymentAccounting.voidAuthorization(
                        customerReserved, customerAvailable, payment.voidAuthorization(NOW)));
                case REFUND -> {
                    payment.refund(op.amount(), NOW);
                    post(PaymentAccounting.refund(merchantAvailable, customerAvailable, op.amount()));
                }
                case OPEN_DISPUTE -> {
                    payment.openDispute(op.amount(), NOW);
                    post(DisputeAccounting.opened(merchantAvailable, merchantHold, op.amount()));
                }
                case RESOLVE_WON -> {
                    payment.resolveDispute(true, NOW);
                    post(DisputeAccounting.won(merchantHold, merchantAvailable, payment.getDisputedAmountMinor()));
                }
                case RESOLVE_LOST -> {
                    payment.resolveDispute(false, NOW);
                    post(DisputeAccounting.lost(merchantHold, customerAvailable, payment.getDisputedAmountMinor()));
                }
            }
        }

        void post(List<PostingLine> lines) {
            assertThat(lines.stream().mapToLong(PostingLine::amountMinor).sum()).isZero();
            request(lines); // the production guard must accept every plan the domain produces
            lines.forEach(line -> raw.merge(line.ledgerAccountId(), line.amountMinor(), Long::sum));
        }

        long presented(UUID account) {
            return purposes.get(account).presented(raw.getOrDefault(account, 0L));
        }

        void assertInvariants(Payment p) {
            assertThat(raw.values().stream().mapToLong(Long::longValue).sum()).isZero();
            assertThat(presented(clearing)).isEqualTo(deposited);
            for (UUID account : List.of(customerAvailable, customerReserved, merchantAvailable, merchantHold)) {
                assertThat(presented(account)).isGreaterThanOrEqualTo(0);
            }
            assertThat(p.getCapturedAmountMinor() + p.getReleasedAmountMinor())
                    .isLessThanOrEqualTo(p.getAuthorizedAmountMinor());
            assertThat(p.getRefundedAmountMinor() + p.getDisputeLostAmountMinor())
                    .isLessThanOrEqualTo(p.getCapturedAmountMinor());
            assertThat(presented(customerReserved)).isEqualTo(p.remainingAuthorizationMinor());
            long held = p.getStatus() == PaymentStatus.DISPUTED ? p.getDisputedAmountMinor() : 0;
            assertThat(presented(merchantHold)).isEqualTo(held);
            assertThat(presented(merchantAvailable) + presented(merchantHold)).isEqualTo(p.netSettledMinor());
            assertThat(presented(customerAvailable))
                    .isEqualTo(deposited - p.remainingAuthorizationMinor() - p.netSettledMinor());
        }
    }
}
