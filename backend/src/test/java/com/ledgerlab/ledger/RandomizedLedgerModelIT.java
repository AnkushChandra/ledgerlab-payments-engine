package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.account.AccountService;
import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.dispute.Dispute;
import com.ledgerlab.dispute.DisputeService;
import com.ledgerlab.dispute.api.OpenDisputeRequest;
import com.ledgerlab.dispute.api.ResolveDisputeRequest;
import com.ledgerlab.funds.FundsService;
import com.ledgerlab.funds.api.DepositRequest;
import com.ledgerlab.funds.api.TransferRequest;
import com.ledgerlab.payment.PaymentService;
import com.ledgerlab.payment.PaymentStatus;
import com.ledgerlab.payment.api.AuthorizePaymentRequest;
import com.ledgerlab.payment.api.CapturePaymentRequest;
import com.ledgerlab.payment.api.PaymentResponse;
import com.ledgerlab.payment.api.RefundPaymentRequest;
import com.ledgerlab.payment.api.VoidPaymentRequest;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Model-based randomized test against the real database: executes a long random sequence of every
 * money-moving operation (including ones that should be rejected), then re-derives all balances from
 * raw ledger entries and checks them against payment state. The seed is logged so any failure can
 * be replayed with -Dledgerlab.model.seed=N.
 */
class RandomizedLedgerModelIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RandomizedLedgerModelIT.class);
    private static final int OPERATIONS = 400;

    @Autowired
    FundsService funds;

    @Autowired
    PaymentService payments;

    @Autowired
    DisputeService disputes;

    @Autowired
    AccountService accounts;

    @Autowired
    LedgerService ledger;

    @Test
    void randomOperationSequencesPreserveEveryInvariant() {
        long seed = Long.getLong("ledgerlab.model.seed", System.nanoTime());
        log.info("Randomized ledger model seed = {}", seed);
        Random random = new Random(seed);

        Tenant tenant = fixtures.newTenant();
        CurrentActor actor = tenant.operations().actor();
        List<AccountResponse> customers = List.of(
                fixtures.customer(tenant, "C1"), fixtures.customer(tenant, "C2"), fixtures.customer(tenant, "C3"));
        List<AccountResponse> merchants = List.of(fixtures.merchant(tenant, "M1"), fixtures.merchant(tenant, "M2"));
        List<UUID> paymentIds = new ArrayList<>();
        List<UUID> openDisputes = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;

        for (int i = 0; i < OPERATIONS; i++) {
            try {
                switch (random.nextInt(9)) {
                    case 0 -> funds.deposit(
                            actor,
                            new DepositRequest(pick(random, customers).id(), amount(random, 50_000), null),
                            key());
                    case 1 -> {
                        List<AccountResponse> all = new ArrayList<>(customers);
                        all.addAll(merchants);
                        funds.transfer(
                                actor,
                                new TransferRequest(pick(random, all).id(), pick(random, all).id(),
                                        amount(random, 20_000), null),
                                key());
                    }
                    case 2, 3 -> paymentIds.add(payments
                            .authorize(
                                    actor,
                                    new AuthorizePaymentRequest(pick(random, customers).id(), pick(random, merchants).id(),
                                            amount(random, 30_000), null, null),
                                    key())
                            .body()
                            .id());
                    case 4 -> payments.capture(
                            actor, pick(random, paymentIds),
                            new CapturePaymentRequest(amount(random, 20_000), random.nextBoolean()), key());
                    case 5 -> payments.voidPayment(actor, pick(random, paymentIds), new VoidPaymentRequest(null), key());
                    case 6 -> payments.refund(
                            actor, pick(random, paymentIds), new RefundPaymentRequest(amount(random, 15_000), null),
                            key());
                    case 7 -> openDisputes.add(disputes.open(
                                    actor, pick(random, paymentIds),
                                    new OpenDisputeRequest(random.nextBoolean() ? null : amount(random, 10_000), "random"),
                                    key())
                            .body()
                            .id());
                    default -> {
                        UUID dispute = pick(random, openDisputes);
                        disputes.resolve(
                                actor,
                                dispute,
                                new ResolveDisputeRequest(
                                        random.nextBoolean() ? Dispute.Outcome.WON : Dispute.Outcome.LOST, null),
                                key());
                        openDisputes.remove(dispute);
                    }
                }
                accepted++;
            } catch (ApiException | NoCandidate e) {
                rejected++;
            }
        }
        log.info("Randomized ledger model: {} accepted, {} rejected operations", accepted, rejected);
        assertThat(accepted).isGreaterThan(OPERATIONS / 4);

        IntegrityReport report = ledger.integrity(tenant.organizationId());
        assertThat(report.healthy()).as("integrity report %s (seed %d)", report, seed).isTrue();

        long reserved = 0;
        long merchantFunds = 0;
        for (AccountResponse c : customers) {
            AccountResponse fresh = accounts.get(tenant.organizationId(), c.id());
            assertThat(fresh.availableMinor()).isNotNegative();
            reserved += fresh.heldMinor();
        }
        for (AccountResponse m : merchants) {
            AccountResponse fresh = accounts.get(tenant.organizationId(), m.id());
            assertThat(fresh.availableMinor()).isNotNegative();
            assertThat(fresh.heldMinor()).isNotNegative();
            merchantFunds += fresh.availableMinor() + fresh.heldMinor();
        }

        long expectedReserved = 0;
        long expectedNetSettled = 0;
        for (UUID id : paymentIds) {
            PaymentResponse p = payments.get(tenant.organizationId(), id);
            assertThat(p.capturedAmountMinor() + p.releasedAmountMinor()).isLessThanOrEqualTo(p.authorizedAmountMinor());
            assertThat(p.refundedAmountMinor() + p.disputeLostAmountMinor()).isLessThanOrEqualTo(p.capturedAmountMinor());
            if (p.status() != PaymentStatus.FAILED) {
                expectedReserved += p.remainingAuthorizationMinor();
            }
            expectedNetSettled += p.netSettledMinor();
        }
        long transfersIntoMerchants = jdbc.queryForObject(
                """
                SELECT coalesce(sum(CASE WHEN d.account_type = 'MERCHANT' THEN t.amount_minor ELSE 0 END), 0)
                     - coalesce(sum(CASE WHEN s.account_type = 'MERCHANT' THEN t.amount_minor ELSE 0 END), 0)
                  FROM transfer t
                  JOIN financial_account s ON s.id = t.source_account_id
                  JOIN financial_account d ON d.id = t.destination_account_id
                 WHERE t.organization_id = ?
                """,
                Long.class,
                tenant.organizationId());
        assertThat(reserved).as("reserved funds equal open authorizations (seed %d)", seed).isEqualTo(expectedReserved);
        assertThat(merchantFunds)
                .as("merchant funds equal net settled plus net transfers (seed %d)", seed)
                .isEqualTo(expectedNetSettled + transfersIntoMerchants);
    }

    private static long amount(Random random, int max) {
        return 1 + random.nextInt(max);
    }

    private static <T> T pick(Random random, List<T> items) {
        if (items.isEmpty()) {
            throw new NoCandidate();
        }
        return items.get(random.nextInt(items.size()));
    }

    private static String key() {
        return TestFixtures.newKey();
    }

    private static final class NoCandidate extends RuntimeException {}
}
