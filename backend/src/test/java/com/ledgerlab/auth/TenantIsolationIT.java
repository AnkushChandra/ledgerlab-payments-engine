package com.ledgerlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One organization must never read or act on another organization's records. Foreign records
 * return 404 (not 403) so their existence is not revealed.
 */
class TenantIsolationIT extends IntegrationTest {

    Tenant victim;
    Tenant intruder;
    AccountResponse victimCustomer;
    AccountResponse victimMerchant;
    String victimPaymentId;
    String victimDisputeId;
    String victimJournalId;

    @BeforeEach
    void setUp() {
        victim = fixtures.newTenant();
        intruder = fixtures.newTenant();
        victimCustomer = fixtures.fundedCustomer(victim, "Victim Customer", 30_000);
        victimMerchant = fixtures.merchant(victim, "Victim Merchant");
        String ops = victim.operations().token();
        Response payment = api.post("/api/v1/payments", ops,
                Map.of("customerAccountId", victimCustomer.id(), "merchantAccountId", victimMerchant.id(),
                        "amountMinor", 10_000),
                TestFixtures.newKey());
        victimPaymentId = payment.body().get("id").asText();
        api.post("/api/v1/payments/" + victimPaymentId + "/captures", ops, Map.of("amountMinor", 10_000),
                TestFixtures.newKey());
        victimDisputeId = api.post("/api/v1/payments/" + victimPaymentId + "/disputes", ops,
                        Map.of("amountMinor", 1_000, "reason", "Fraud"), TestFixtures.newKey())
                .body().get("id").asText();
        victimJournalId = api.get("/api/v1/payments/" + victimPaymentId + "/events", ops)
                .body().get(0).get("ledgerTransactionId").asText();
    }

    @Test
    void cannotReadAnotherOrganizationsAccounts() {
        String token = intruder.admin().token();
        assertThat(api.get("/api/v1/accounts/" + victimCustomer.id(), token).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/accounts/" + victimCustomer.id() + "/ledger-entries", token).status())
                .isEqualTo(404);
        assertThat(api.get("/api/v1/accounts", token).body().get("totalItems").asInt()).isZero();
    }

    @Test
    void cannotReadAnotherOrganizationsPaymentsDisputesOrJournals() {
        String token = intruder.admin().token();
        assertThat(api.get("/api/v1/payments/" + victimPaymentId, token).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/payments/" + victimPaymentId + "/events", token).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/payments/" + victimPaymentId + "/refunds", token).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/payments", token).body().get("totalItems").asInt()).isZero();
        assertThat(api.get("/api/v1/payments?q=" + victimPaymentId, token).body().get("totalItems").asInt())
                .isZero();
        assertThat(api.get("/api/v1/disputes/" + victimDisputeId, token).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/disputes", token).body().get("totalItems").asInt()).isZero();
        assertThat(api.get("/api/v1/ledger/transactions/" + victimJournalId, token).status()).isEqualTo(404);
    }

    @Test
    void cannotReadAnotherOrganizationsAuditEvents() {
        Response events = api.get("/api/v1/audit-events?targetId=" + victimPaymentId, intruder.admin().token());
        assertThat(events.status()).isEqualTo(200);
        assertThat(events.body().get("totalItems").asInt()).isZero();
        Response all = api.get("/api/v1/audit-events?size=100", intruder.admin().token());
        all.body().get("items").forEach(e -> assertThat(e.get("targetId").asText()).isNotEqualTo(victimPaymentId));
    }

    @Test
    void cannotMoveAnotherOrganizationsMoney() {
        String ops = intruder.operations().token();
        AccountResponse own = fixtures.fundedCustomer(intruder, "Intruder", 5_000);

        assertThat(api.post("/api/v1/payments/" + victimPaymentId + "/refunds", ops, Map.of("amountMinor", 100),
                                TestFixtures.newKey())
                        .status())
                .isEqualTo(404);
        assertThat(api.post("/api/v1/disputes/" + victimDisputeId + "/resolution", ops, Map.of("outcome", "LOST"),
                                TestFixtures.newKey())
                        .status())
                .isEqualTo(404);
        assertThat(api.post("/api/v1/deposits", ops, Map.of("accountId", victimCustomer.id(), "amountMinor", 100),
                                TestFixtures.newKey())
                        .status())
                .isEqualTo(404);
        assertThat(api.post("/api/v1/transfers", ops,
                                Map.of("sourceAccountId", victimCustomer.id(), "destinationAccountId", own.id(),
                                        "amountMinor", 100),
                                TestFixtures.newKey())
                        .status())
                .isEqualTo(404);
        assertThat(api.post("/api/v1/payments", ops,
                                Map.of("customerAccountId", own.id(), "merchantAccountId", victimMerchant.id(),
                                        "amountMinor", 100),
                                TestFixtures.newKey())
                        .status())
                .isEqualTo(404);

        Response victimCustomerNow = api.get("/api/v1/accounts/" + victimCustomer.id(), victim.viewer().token());
        assertThat(victimCustomerNow.body().get("availableMinor").asLong()).isEqualTo(20_000);
    }
}
