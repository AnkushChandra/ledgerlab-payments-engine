package com.ledgerlab.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.funds.FundsService;
import com.ledgerlab.funds.api.DepositRequest;
import com.ledgerlab.funds.api.DepositResponse;
import com.ledgerlab.payment.PaymentService;
import com.ledgerlab.payment.api.AuthorizePaymentRequest;
import com.ledgerlab.payment.api.CapturePaymentRequest;
import com.ledgerlab.payment.api.PaymentResponse;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.Concurrently;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyIT extends IntegrationTest {

    @Autowired
    FundsService fundsService;

    @Autowired
    PaymentService paymentService;

    Tenant tenant;
    AccountResponse customer;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        customer = fixtures.customer(tenant, "Grace");
    }

    @Test
    void repeatingKeyAndPayloadReturnsOriginalResultWithoutSecondOperation() {
        String key = TestFixtures.newKey();
        Map<String, Object> body = Map.of("accountId", customer.id(), "amountMinor", 2_500, "memo", "top up");

        Response first = api.post("/api/v1/deposits", tenant.operations().token(), body, key);
        Response second = api.post("/api/v1/deposits", tenant.operations().token(), body, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(first.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(depositCount()).isEqualTo(1);
        assertThat(journalCount()).isEqualTo(1);
        assertThat(available()).isEqualTo(2_500);
    }

    @Test
    void reusingKeyWithDifferentPayloadIsAConflict() {
        String key = TestFixtures.newKey();
        api.post("/api/v1/deposits", tenant.operations().token(),
                Map.of("accountId", customer.id(), "amountMinor", 2_500), key);
        Response conflict = api.post("/api/v1/deposits", tenant.operations().token(),
                Map.of("accountId", customer.id(), "amountMinor", 9_999), key);

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(depositCount()).isEqualTo(1);
        assertThat(available()).isEqualTo(2_500);
    }

    @Test
    void keysAreScopedPerOperationAndPerOrganization() {
        String key = TestFixtures.newKey();
        fundsService.deposit(tenant.operations().actor(), new DepositRequest(customer.id(), 1_000, null), key);

        AccountResponse merchant = fixtures.merchant(tenant, "Shop");
        var payment = paymentService.authorize(
                tenant.operations().actor(),
                new AuthorizePaymentRequest(customer.id(), merchant.id(), 500, null, null),
                key);
        assertThat(payment.replayed()).isFalse();

        Tenant other = fixtures.newTenant();
        AccountResponse otherCustomer = fixtures.customer(other, "Other");
        var otherDeposit = fundsService.deposit(
                other.operations().actor(), new DepositRequest(otherCustomer.id(), 1_000, null), key);
        assertThat(otherDeposit.replayed()).isFalse();
    }

    @Test
    void retriedCaptureIsAppliedOnce() {
        fixtures.deposit(tenant, customer.id(), 10_000);
        AccountResponse merchant = fixtures.merchant(tenant, "Shop");
        PaymentResponse payment = paymentService
                .authorize(
                        tenant.operations().actor(),
                        new AuthorizePaymentRequest(customer.id(), merchant.id(), 10_000, null, null),
                        TestFixtures.newKey())
                .body();
        String key = TestFixtures.newKey();
        var request = new CapturePaymentRequest(4_000, null);
        var first = paymentService.capture(tenant.operations().actor(), payment.id(), request, key);
        var retry = paymentService.capture(
                tenant.operations().actor(), payment.id(), new CapturePaymentRequest(4_000, false), key);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(paymentService.get(tenant.organizationId(), payment.id()).capturedAmountMinor())
                .isEqualTo(4_000);
    }

    @Test
    void failedRequestDoesNotConsumeTheKey() {
        String key = TestFixtures.newKey();
        Response rejected = api.post(
                "/api/v1/transfers",
                tenant.operations().token(),
                Map.of("sourceAccountId", customer.id(), "destinationAccountId", fixtures.customer(tenant, "B").id(),
                        "amountMinor", 5_000),
                key);
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.code()).isEqualTo("INSUFFICIENT_FUNDS");

        fixtures.deposit(tenant, customer.id(), 5_000);
        Response retried = api.post(
                "/api/v1/transfers",
                tenant.operations().token(),
                Map.of("sourceAccountId", customer.id(), "destinationAccountId", fixtures.customer(tenant, "C").id(),
                        "amountMinor", 5_000),
                key);
        assertThat(retried.status()).isEqualTo(201);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOneOperation() {
        String key = TestFixtures.newKey();
        var request = new DepositRequest(customer.id(), 7_777, "concurrent");

        var outcomes = Concurrently.run(12, i -> () -> fundsService.deposit(tenant.operations().actor(), request, key));

        List<IdempotentResult<DepositResponse>> results = Concurrently.successes(outcomes);
        assertThat(Concurrently.failures(outcomes)).isEmpty();
        assertThat(results).hasSize(12);
        assertThat(results.stream().map(r -> r.body().id()).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(depositCount()).isEqualTo(1);
        assertThat(journalCount()).isEqualTo(1);
        assertThat(available()).isEqualTo(7_777);
    }

    @Test
    void invalidKeyFormatIsRejected() {
        Response response = api.post(
                "/api/v1/deposits",
                tenant.operations().token(),
                Map.of("accountId", customer.id(), "amountMinor", 100),
                "short");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }

    private long depositCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM deposit WHERE organization_id = ?", Long.class, tenant.organizationId());
    }

    private long journalCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transaction WHERE organization_id = ?", Long.class, tenant.organizationId());
    }

    private long available() {
        return api.get("/api/v1/accounts/" + customer.id(), tenant.viewer().token())
                .body()
                .get("availableMinor")
                .asLong();
    }
}
