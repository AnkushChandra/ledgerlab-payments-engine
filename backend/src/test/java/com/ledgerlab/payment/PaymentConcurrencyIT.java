package com.ledgerlab.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.account.AccountService;
import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.funds.FundsService;
import com.ledgerlab.funds.api.TransferRequest;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.payment.api.AuthorizePaymentRequest;
import com.ledgerlab.payment.api.CapturePaymentRequest;
import com.ledgerlab.payment.api.PaymentResponse;
import com.ledgerlab.payment.api.RefundPaymentRequest;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.support.Concurrently;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Competing operations executed on parallel threads, each in its own database transaction. The
 * database (row locks + constraints) must serialize them so no invariant is ever violated.
 */
class PaymentConcurrencyIT extends IntegrationTest {

    @Autowired
    PaymentService paymentService;

    @Autowired
    FundsService fundsService;

    @Autowired
    AccountService accountService;

    @Autowired
    LedgerService ledgerService;

    Tenant tenant;
    AccountResponse customer;
    AccountResponse merchant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        customer = fixtures.fundedCustomer(tenant, "Ada", 100_000);
        merchant = fixtures.merchant(tenant, "Shop");
    }

    @RepeatedTest(3)
    void concurrentCapturesCannotOverCapture() {
        PaymentResponse payment = authorize(10_000);

        var outcomes = Concurrently.run(10, i -> () -> paymentService.capture(
                tenant.operations().actor(), payment.id(), new CapturePaymentRequest(2_000, null),
                TestFixtures.newKey()));

        assertThat(Concurrently.successes(outcomes)).hasSize(5);
        assertThat(Concurrently.failures(outcomes))
                .hasSize(5)
                .allSatisfy(e -> assertThat(((ApiException) e).code())
                        .isIn(ErrorCode.CAPTURE_EXCEEDS_AUTHORIZATION, ErrorCode.INVALID_PAYMENT_STATE));
        PaymentResponse after = paymentService.get(tenant.organizationId(), payment.id());
        assertThat(after.capturedAmountMinor()).isEqualTo(10_000);
        assertThat(after.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(account(merchant).availableMinor()).isEqualTo(10_000);
        assertThat(account(customer).heldMinor()).isZero();
        assertThat(ledgerService.integrity(tenant.organizationId()).healthy()).isTrue();
    }

    @Test
    void twoConcurrentFullCapturesProduceExactlyOneCapture() {
        PaymentResponse payment = authorize(25_000);

        var outcomes = Concurrently.run(2, i -> () -> paymentService.capture(
                tenant.operations().actor(), payment.id(), new CapturePaymentRequest(25_000, null),
                TestFixtures.newKey()));

        assertThat(Concurrently.successes(outcomes)).hasSize(1);
        assertThat(account(merchant).availableMinor()).isEqualTo(25_000);
        assertThat(captureEvents(payment)).isEqualTo(1);
    }

    @RepeatedTest(3)
    void concurrentRefundsCannotExceedCapturedAmount() {
        PaymentResponse payment = authorize(10_000);
        paymentService.capture(
                tenant.operations().actor(), payment.id(), new CapturePaymentRequest(10_000, null),
                TestFixtures.newKey());

        var outcomes = Concurrently.run(8, i -> () -> paymentService.refund(
                tenant.operations().actor(), payment.id(), new RefundPaymentRequest(3_000, "race"),
                TestFixtures.newKey()));

        assertThat(Concurrently.successes(outcomes)).hasSize(3);
        assertThat(Concurrently.failures(outcomes))
                .allSatisfy(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.REFUND_EXCEEDS_CAPTURED));
        PaymentResponse after = paymentService.get(tenant.organizationId(), payment.id());
        assertThat(after.refundedAmountMinor()).isEqualTo(9_000);
        assertThat(account(merchant).availableMinor()).isEqualTo(1_000);
        assertThat(ledgerService.integrity(tenant.organizationId()).healthy()).isTrue();
    }

    @Test
    void concurrentAuthorizationsCannotSpendTheSameFundsTwice() {
        AccountResponse small = fixtures.fundedCustomer(tenant, "Small", 10_000);

        var outcomes = Concurrently.run(20, i -> () -> paymentService
                .authorize(
                        tenant.operations().actor(),
                        new AuthorizePaymentRequest(small.id(), merchant.id(), 1_000, null, null),
                        TestFixtures.newKey())
                .body());

        var payments = Concurrently.successes(outcomes);
        assertThat(payments).hasSize(20);
        assertThat(payments.stream().filter(p -> p.status() == PaymentStatus.AUTHORIZED)).hasSize(10);
        assertThat(payments.stream().filter(p -> p.status() == PaymentStatus.FAILED)).hasSize(10);
        AccountResponse after = account(small);
        assertThat(after.availableMinor()).isZero();
        assertThat(after.heldMinor()).isEqualTo(10_000);
    }

    @Test
    void opposingConcurrentTransfersDoNotDeadlockAndConserveMoney() {
        AccountResponse a = fixtures.fundedCustomer(tenant, "A", 50_000);
        AccountResponse b = fixtures.fundedCustomer(tenant, "B", 50_000);

        var outcomes = Concurrently.run(24, i -> () -> {
            boolean forward = i % 2 == 0;
            return fundsService.transfer(
                    tenant.operations().actor(),
                    new TransferRequest(forward ? a.id() : b.id(), forward ? b.id() : a.id(), 1_000 + i, null),
                    TestFixtures.newKey());
        });

        assertThat(Concurrently.failures(outcomes)).isEmpty();
        assertThat(account(a).availableMinor() + account(b).availableMinor()).isEqualTo(100_000);
        assertThat(ledgerService.integrity(tenant.organizationId()).healthy()).isTrue();
    }

    @Test
    void concurrentTransfersCannotOverdrawTheSource() {
        AccountResponse source = fixtures.fundedCustomer(tenant, "Source", 5_000);
        AccountResponse destination = fixtures.customer(tenant, "Destination");

        var outcomes = Concurrently.run(10, i -> () -> fundsService.transfer(
                tenant.operations().actor(),
                new TransferRequest(source.id(), destination.id(), 1_000, null),
                TestFixtures.newKey()));

        assertThat(Concurrently.successes(outcomes)).hasSize(5);
        assertThat(Concurrently.failures(outcomes))
                .allSatisfy(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        assertThat(account(source).availableMinor()).isZero();
        assertThat(account(destination).availableMinor()).isEqualTo(5_000);
    }

    private PaymentResponse authorize(long amount) {
        return paymentService
                .authorize(
                        tenant.operations().actor(),
                        new AuthorizePaymentRequest(customer.id(), merchant.id(), amount, null, null),
                        TestFixtures.newKey())
                .body();
    }

    private AccountResponse account(AccountResponse account) {
        return accountService.get(tenant.organizationId(), account.id());
    }

    private long captureEvents(PaymentResponse payment) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_event WHERE payment_id = ? AND event_type = 'CAPTURED'",
                Long.class,
                payment.id());
    }
}
