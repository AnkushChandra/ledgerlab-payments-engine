package com.ledgerlab.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Full payment lifecycle through the HTTP API against real PostgreSQL. */
class PaymentLifecycleIT extends IntegrationTest {

    @Autowired
    LedgerService ledgerService;

    Tenant tenant;
    String token;
    AccountResponse customer;
    AccountResponse merchant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        token = tenant.operations().token();
        customer = fixtures.fundedCustomer(tenant, "Ada", 50_000);
        merchant = fixtures.merchant(tenant, "Blue Bottle");
    }

    @Test
    void authorizeCaptureRefundMovesFundsAndRecordsTimeline() {
        JsonNode payment = authorize(20_000).body();
        String id = payment.get("id").asText();
        assertThat(payment.get("status").asText()).isEqualTo("AUTHORIZED");
        assertBalances(30_000, 20_000, 0);

        Response capture = api.post(
                "/api/v1/payments/" + id + "/captures", token, Map.of("amountMinor", 12_000), TestFixtures.newKey());
        assertThat(capture.status()).isEqualTo(201);
        assertThat(capture.body().get("status").asText()).isEqualTo("PARTIALLY_CAPTURED");
        assertBalances(30_000, 8_000, 12_000);

        Response finalCapture = api.post(
                "/api/v1/payments/" + id + "/captures",
                token,
                Map.of("amountMinor", 5_000, "finalCapture", true),
                TestFixtures.newKey());
        assertThat(finalCapture.body().get("status").asText()).isEqualTo("CAPTURED");
        assertThat(finalCapture.body().get("releasedAmountMinor").asLong()).isEqualTo(3_000);
        assertBalances(33_000, 0, 17_000);

        Response refund = api.post(
                "/api/v1/payments/" + id + "/refunds",
                token,
                Map.of("amountMinor", 7_000, "reason", "returned"),
                TestFixtures.newKey());
        assertThat(refund.status()).isEqualTo(201);
        assertThat(refund.body().at("/payment/status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(refund.body().at("/refund/amountMinor").asLong()).isEqualTo(7_000);
        assertBalances(40_000, 0, 10_000);

        Response events = api.get("/api/v1/payments/" + id + "/events", token);
        assertThat(events.body().findValuesAsText("type"))
                .containsExactly("AUTHORIZED", "CAPTURED", "CAPTURED", "REFUNDED");
        events.body().forEach(e -> assertThat(e.get("ledgerTransactionId").isNull()).isFalse());

        Response journal = api.get(
                "/api/v1/ledger/transactions/" + events.body().get(2).get("ledgerTransactionId").asText(), token);
        assertThat(journal.body().get("entries")).hasSize(4);
        long sum = 0;
        for (JsonNode entry : journal.body().get("entries")) {
            sum += entry.get("amountMinor").asLong();
        }
        assertThat(sum).isZero();

        assertThat(ledgerService.integrity(tenant.organizationId()).healthy()).isTrue();
    }

    @Test
    void voidReleasesReservedFunds() {
        String id = authorize(15_000).body().get("id").asText();
        Response voided = api.post("/api/v1/payments/" + id + "/void", token, Map.of("reason", "cancelled"),
                TestFixtures.newKey());
        assertThat(voided.status()).isEqualTo(200);
        assertThat(voided.body().get("status").asText()).isEqualTo("VOIDED");
        assertBalances(50_000, 0, 0);

        Response secondCapture = api.post(
                "/api/v1/payments/" + id + "/captures", token, Map.of("amountMinor", 1), TestFixtures.newKey());
        assertThat(secondCapture.status()).isEqualTo(409);
        assertThat(secondCapture.code()).isEqualTo("INVALID_PAYMENT_STATE");
    }

    @Test
    void insufficientFundsProducesFailedPaymentWithoutLedgerActivity() {
        long journalsBefore = journalCount();
        Response response = authorize(60_000);
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("status").asText()).isEqualTo("FAILED");
        assertThat(response.body().get("failureCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(journalCount()).isEqualTo(journalsBefore);
        assertBalances(50_000, 0, 0);
    }

    @Test
    void frozenCustomerAuthorizationFails() {
        api.patch("/api/v1/accounts/" + customer.id(), tenant.admin().token(), Map.of("status", "FROZEN"));
        Response response = authorize(1_000);
        assertThat(response.body().get("status").asText()).isEqualTo("FAILED");
        assertThat(response.body().get("failureCode").asText()).isEqualTo("CUSTOMER_ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void overCaptureAndOverRefundAreRejectedWith422() {
        String id = authorize(10_000).body().get("id").asText();
        Response over = api.post(
                "/api/v1/payments/" + id + "/captures", token, Map.of("amountMinor", 10_001), TestFixtures.newKey());
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("CAPTURE_EXCEEDS_AUTHORIZATION");

        api.post("/api/v1/payments/" + id + "/captures", token, Map.of("amountMinor", 10_000), TestFixtures.newKey());
        Response overRefund = api.post(
                "/api/v1/payments/" + id + "/refunds", token, Map.of("amountMinor", 10_001), TestFixtures.newKey());
        assertThat(overRefund.status()).isEqualTo(422);
        assertThat(overRefund.code()).isEqualTo("REFUND_EXCEEDS_CAPTURED");
        assertThat(overRefund.body().get("correlationId").asText()).isNotBlank();
    }

    @Test
    void wrongAccountTypesAreRejected() {
        Response response = api.post(
                "/api/v1/payments",
                token,
                Map.of("customerAccountId", merchant.id(), "merchantAccountId", customer.id(), "amountMinor", 100),
                TestFixtures.newKey());
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("INVALID_ACCOUNT_TYPE");
    }

    @Test
    void validationErrorsAreStructured() {
        Response response = api.post(
                "/api/v1/payments",
                token,
                Map.of("customerAccountId", customer.id(), "amountMinor", 0),
                TestFixtures.newKey());
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body().get("errors").findValuesAsText("field"))
                .contains("amountMinor", "merchantAccountId");
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        Response response = api.post(
                "/api/v1/payments",
                token,
                Map.of("customerAccountId", customer.id(), "merchantAccountId", merchant.id(), "amountMinor", 100));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void listSupportsStatusFilterAndDeterministicPaging() {
        for (int i = 0; i < 3; i++) {
            authorize(1_000 + i);
        }
        String capturedId = authorize(500).body().get("id").asText();
        api.post("/api/v1/payments/" + capturedId + "/captures", token, Map.of("amountMinor", 500),
                TestFixtures.newKey());

        Response captured = api.get("/api/v1/payments?status=CAPTURED", token);
        assertThat(captured.body().get("totalItems").asInt()).isEqualTo(1);
        assertThat(captured.body().at("/items/0/id").asText()).isEqualTo(capturedId);

        Response page0 = api.get("/api/v1/payments?size=2&page=0&sort=authorizedAmountMinor,asc", token);
        Response page1 = api.get("/api/v1/payments?size=2&page=1&sort=authorizedAmountMinor,asc", token);
        assertThat(page0.body().get("totalItems").asInt()).isEqualTo(4);
        assertThat(page0.body().get("items").findValuesAsText("authorizedAmountMinor"))
                .containsExactly("500", "1000");
        assertThat(page1.body().get("items").findValuesAsText("authorizedAmountMinor"))
                .containsExactly("1001", "1002");

        Response badSort = api.get("/api/v1/payments?sort=customerAccountId", token);
        assertThat(badSort.status()).isEqualTo(400);
    }

    private Response authorize(long amount) {
        return api.post(
                "/api/v1/payments",
                token,
                Map.of(
                        "customerAccountId", customer.id(),
                        "merchantAccountId", merchant.id(),
                        "amountMinor", amount,
                        "reference", "ORDER-" + UUID.randomUUID().toString().substring(0, 6)),
                TestFixtures.newKey());
    }

    private void assertBalances(long customerAvailable, long customerReserved, long merchantAvailable) {
        JsonNode c = api.get("/api/v1/accounts/" + customer.id(), token).body();
        JsonNode m = api.get("/api/v1/accounts/" + merchant.id(), token).body();
        assertThat(List.of(c.get("availableMinor").asLong(), c.get("heldMinor").asLong(), m.get("availableMinor").asLong()))
                .containsExactly(customerAvailable, customerReserved, merchantAvailable);
    }

    private long journalCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transaction WHERE organization_id = ?", Long.class, tenant.organizationId());
    }
}
