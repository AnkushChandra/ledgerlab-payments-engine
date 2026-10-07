package com.ledgerlab.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.ledgerlab.account.AccountService;
import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditService;
import com.ledgerlab.payment.api.AuthorizePaymentRequest;
import com.ledgerlab.payment.api.PaymentResponse;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Injects a failure after the ledger journal has been written but before the transaction commits,
 * and proves that payment state, ledger entries, timeline events and the idempotency key are all
 * rolled back together.
 */
class PaymentRollbackIT extends IntegrationTest {

    @MockitoSpyBean
    AuditService auditService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    AccountService accountService;

    Tenant tenant;
    AccountResponse customer;
    AccountResponse merchant;
    PaymentResponse payment;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        customer = fixtures.fundedCustomer(tenant, "Ada", 20_000);
        merchant = fixtures.merchant(tenant, "Shop");
        payment = paymentService
                .authorize(
                        tenant.operations().actor(),
                        new AuthorizePaymentRequest(customer.id(), merchant.id(), 10_000, null, null),
                        TestFixtures.newKey())
                .body();
    }

    @AfterEach
    void tearDown() {
        reset(auditService);
    }

    @Test
    void failureAfterLedgerPostingRollsBackEverything() {
        failAuditFor(AuditAction.PAYMENT_CAPTURED);
        long journalsBefore = count("ledger_transaction");
        long entriesBefore = count("ledger_entry");
        long eventsBefore = count("payment_event");
        String key = TestFixtures.newKey();

        Response response = capture(key, 10_000);

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.text()).doesNotContain("SQL", "Exception", "injected");

        PaymentResponse after = paymentService.get(tenant.organizationId(), payment.id());
        assertThat(after.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(after.capturedAmountMinor()).isZero();
        assertThat(count("ledger_transaction")).isEqualTo(journalsBefore);
        assertThat(count("ledger_entry")).isEqualTo(entriesBefore);
        assertThat(count("payment_event")).isEqualTo(eventsBefore);
        assertThat(accountService.get(tenant.organizationId(), merchant.id()).availableMinor()).isZero();
        assertThat(accountService.get(tenant.organizationId(), customer.id()).heldMinor()).isEqualTo(10_000);

        // The key was not consumed: once the fault is gone, the same retry succeeds.
        reset(auditService);
        Response retry = capture(key, 10_000);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("false");
        assertThat(accountService.get(tenant.organizationId(), merchant.id()).availableMinor()).isEqualTo(10_000);
    }

    @Test
    void failedRefundLeavesNoRefundRow() {
        capture(TestFixtures.newKey(), 10_000);
        failAuditFor(AuditAction.PAYMENT_REFUNDED);

        Response response = api.post(
                "/api/v1/payments/" + payment.id() + "/refunds",
                tenant.operations().token(),
                Map.of("amountMinor", 4_000),
                TestFixtures.newKey());

        assertThat(response.status()).isEqualTo(500);
        assertThat(count("refund")).isZero();
        PaymentResponse after = paymentService.get(tenant.organizationId(), payment.id());
        assertThat(after.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(after.refundedAmountMinor()).isZero();
    }

    private Response capture(String key, long amount) {
        return api.post(
                "/api/v1/payments/" + payment.id() + "/captures",
                tenant.operations().token(),
                Map.of("amountMinor", amount),
                key);
    }

    private void failAuditFor(AuditAction action) {
        doThrow(new IllegalStateException("injected failure"))
                .when(auditService)
                .record(any(CurrentActor.class), eq(action), any(), any(UUID.class), any());
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id = ?", Long.class, tenant.organizationId());
    }
}
