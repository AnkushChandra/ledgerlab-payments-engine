package com.ledgerlab.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DisputeIT extends IntegrationTest {

    @Autowired
    LedgerService ledgerService;

    Tenant tenant;
    String token;
    AccountResponse customer;
    AccountResponse merchant;
    String paymentId;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        token = tenant.operations().token();
        customer = fixtures.fundedCustomer(tenant, "Ada", 50_000);
        merchant = fixtures.merchant(tenant, "Shop");
        paymentId = api.post(
                        "/api/v1/payments",
                        token,
                        Map.of("customerAccountId", customer.id(), "merchantAccountId", merchant.id(),
                                "amountMinor", 20_000),
                        TestFixtures.newKey())
                .body()
                .get("id")
                .asText();
        api.post("/api/v1/payments/" + paymentId + "/captures", token, Map.of("amountMinor", 20_000),
                TestFixtures.newKey());
    }

    @Test
    void openingADisputeFreezesMerchantFunds() {
        Response opened = open(Map.of("amountMinor", 8_000, "reason", "Item not received"));
        assertThat(opened.status()).isEqualTo(201);
        assertThat(opened.body().get("status").asText()).isEqualTo("OPEN");
        assertThat(opened.body().at("/payment/status").asText()).isEqualTo("DISPUTED");

        JsonNode m = account(merchant);
        assertThat(m.get("availableMinor").asLong()).isEqualTo(12_000);
        assertThat(m.get("heldMinor").asLong()).isEqualTo(8_000);

        Response refundWhileDisputed = api.post(
                "/api/v1/payments/" + paymentId + "/refunds", token, Map.of("amountMinor", 100), TestFixtures.newKey());
        assertThat(refundWhileDisputed.status()).isEqualTo(409);

        Response secondOpen = open(Map.of("reason", "again"));
        assertThat(secondOpen.status()).isEqualTo(409);
        assertThat(secondOpen.code()).isEqualTo("INVALID_PAYMENT_STATE");
    }

    @Test
    void lostDisputeChargesBackToCustomer() {
        String disputeId = open(Map.of("reason", "Fraud")).body().get("id").asText();
        assertThat(account(merchant).get("heldMinor").asLong()).isEqualTo(20_000);

        Response resolved = resolve(disputeId, "LOST");
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().get("status").asText()).isEqualTo("LOST");
        assertThat(resolved.body().at("/payment/status").asText()).isEqualTo("RESOLVED");

        assertThat(account(merchant).get("availableMinor").asLong()).isZero();
        assertThat(account(merchant).get("heldMinor").asLong()).isZero();
        assertThat(account(customer).get("availableMinor").asLong()).isEqualTo(50_000);

        Response events = api.get("/api/v1/payments/" + paymentId + "/events", token);
        assertThat(events.body().findValuesAsText("type"))
                .containsExactly("AUTHORIZED", "CAPTURED", "DISPUTE_OPENED", "DISPUTE_LOST");
        JsonNode payment = api.get("/api/v1/payments/" + paymentId, token).body();
        assertThat(payment.get("netSettledMinor").asLong()).isZero();
        assertThat(ledgerService.integrity(tenant.organizationId()).healthy()).isTrue();
    }

    @Test
    void wonDisputeReleasesHoldToMerchant() {
        String disputeId = open(Map.of("amountMinor", 5_000, "reason", "Wrong size")).body().get("id").asText();
        resolve(disputeId, "WON");

        JsonNode m = account(merchant);
        assertThat(m.get("availableMinor").asLong()).isEqualTo(20_000);
        assertThat(m.get("heldMinor").asLong()).isZero();
        assertThat(account(customer).get("availableMinor").asLong()).isEqualTo(30_000);
    }

    @Test
    void resolvingTwiceIsRejectedAndRetryWithSameKeyReplays() {
        String disputeId = open(Map.of("reason", "Fraud")).body().get("id").asText();
        String key = TestFixtures.newKey();
        Map<String, String> body = Map.of("outcome", "WON");
        Response first = api.post("/api/v1/disputes/" + disputeId + "/resolution", token, body, key);
        Response replay = api.post("/api/v1/disputes/" + disputeId + "/resolution", token, body, key);
        Response again = resolve(disputeId, "LOST");

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.status()).isEqualTo(409);
    }

    @Test
    void merchantWithoutFundsCannotBeDisputed() {
        AccountResponse other = fixtures.customer(tenant, "Other");
        api.post(
                "/api/v1/transfers",
                token,
                Map.of("sourceAccountId", merchant.id(), "destinationAccountId", other.id(), "amountMinor", 19_000),
                TestFixtures.newKey());

        Response response = open(Map.of("amountMinor", 5_000, "reason", "Fraud"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("MERCHANT_INSUFFICIENT_FUNDS");
        assertThat(api.get("/api/v1/payments/" + paymentId, token).body().get("status").asText())
                .isEqualTo("CAPTURED");
    }

    @Test
    void disputeQueueFiltersByStatus() {
        open(Map.of("reason", "Fraud"));
        Response openQueue = api.get("/api/v1/disputes?status=OPEN", tenant.viewer().token());
        Response wonQueue = api.get("/api/v1/disputes?status=WON", tenant.viewer().token());
        assertThat(openQueue.body().get("totalItems").asInt()).isEqualTo(1);
        assertThat(openQueue.body().at("/items/0/payment/id").asText()).isEqualTo(paymentId);
        assertThat(wonQueue.body().get("totalItems").asInt()).isZero();
    }

    private Response open(Map<String, ?> body) {
        return api.post("/api/v1/payments/" + paymentId + "/disputes", token, body, TestFixtures.newKey());
    }

    private Response resolve(String disputeId, String outcome) {
        return api.post(
                "/api/v1/disputes/" + disputeId + "/resolution",
                token,
                Map.of("outcome", outcome, "note", "reviewed"),
                TestFixtures.newKey());
    }

    private JsonNode account(AccountResponse account) {
        return api.get("/api/v1/accounts/" + account.id(), token).body();
    }
}
