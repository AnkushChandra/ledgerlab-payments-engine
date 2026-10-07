package com.ledgerlab.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.support.ApiClient;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AuditTrailIT extends IntegrationTest {

    Tenant tenant;
    AccountResponse customer;
    AccountResponse merchant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        customer = fixtures.fundedCustomer(tenant, "Ada", 20_000);
        merchant = fixtures.merchant(tenant, "Shop");
    }

    @Test
    void businessOperationsAreAuditedWithActorTargetAndCorrelationId() {
        Response payment = api.perform(ApiClient.withAuth(MockMvcRequestBuilders.post("/api/v1/payments"),
                        tenant.operations().token())
                .header("Idempotency-Key", TestFixtures.newKey())
                .header("X-Correlation-Id", "audit-test-correlation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(api.json(Map.of(
                        "customerAccountId", customer.id(), "merchantAccountId", merchant.id(), "amountMinor", 4_000))));
        String paymentId = payment.body().get("id").asText();

        Response events = api.get(
                "/api/v1/audit-events?targetType=PAYMENT&targetId=" + paymentId, tenant.admin().token());
        assertThat(events.body().get("totalItems").asInt()).isEqualTo(1);
        JsonNode event = events.body().get("items").get(0);
        assertThat(event.get("action").asText()).isEqualTo("PAYMENT_AUTHORIZED");
        assertThat(event.get("actorUserId").asText()).isEqualTo(tenant.operations().actor().userId().toString());
        assertThat(event.get("actorEmail").asText()).isEqualTo(tenant.operations().email());
        assertThat(event.get("correlationId").asText()).isEqualTo("audit-test-correlation");
        assertThat(event.at("/details/amountMinor").asLong()).isEqualTo(4_000);
    }

    @Test
    void everyMoneyMovementLeavesAnAuditEvent() {
        String ops = tenant.operations().token();
        String paymentId = api.post("/api/v1/payments", ops,
                        Map.of("customerAccountId", customer.id(), "merchantAccountId", merchant.id(),
                                "amountMinor", 6_000),
                        TestFixtures.newKey())
                .body().get("id").asText();
        api.post("/api/v1/payments/" + paymentId + "/captures", ops, Map.of("amountMinor", 6_000),
                TestFixtures.newKey());
        api.post("/api/v1/payments/" + paymentId + "/refunds", ops, Map.of("amountMinor", 1_000),
                TestFixtures.newKey());
        String disputeId = api.post("/api/v1/payments/" + paymentId + "/disputes", ops, Map.of("reason", "Fraud"),
                        TestFixtures.newKey())
                .body().get("id").asText();
        api.post("/api/v1/disputes/" + disputeId + "/resolution", ops, Map.of("outcome", "WON"),
                TestFixtures.newKey());
        String voidable = api.post("/api/v1/payments", ops,
                        Map.of("customerAccountId", customer.id(), "merchantAccountId", merchant.id(),
                                "amountMinor", 100),
                        TestFixtures.newKey())
                .body().get("id").asText();
        api.post("/api/v1/payments/" + voidable + "/void", ops, Map.of(), TestFixtures.newKey());
        api.post("/api/v1/transfers", ops,
                Map.of("sourceAccountId", customer.id(), "destinationAccountId", fixtures.customer(tenant, "B").id(),
                        "amountMinor", 500),
                TestFixtures.newKey());

        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT action FROM audit_event WHERE organization_id = ?",
                        String.class,
                        tenant.organizationId()))
                .contains(
                        "ACCOUNT_CREATED", "DEPOSIT_CREATED", "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED",
                        "PAYMENT_REFUNDED", "DISPUTE_OPENED", "DISPUTE_RESOLVED", "PAYMENT_VOIDED",
                        "TRANSFER_CREATED");

        Response filtered = api.get("/api/v1/audit-events?action=DISPUTE_RESOLVED", tenant.admin().token());
        assertThat(filtered.body().get("totalItems").asInt()).isEqualTo(1);
        assertThat(filtered.body().at("/items/0/details/outcome").asText()).isEqualTo("WON");
    }

    @Test
    void auditEventsAreAppendOnly() {
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE audit_event SET action = 'LOGIN_SUCCEEDED' WHERE organization_id = ?",
                        tenant.organizationId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE organization_id = ?", tenant.organizationId()))
                .hasMessageContaining("append-only");
    }

    @Test
    void paymentEventsAreAppendOnly() {
        api.post("/api/v1/payments", tenant.operations().token(),
                Map.of("customerAccountId", customer.id(), "merchantAccountId", merchant.id(), "amountMinor", 100),
                TestFixtures.newKey());
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payment_event SET amount_minor = 0 WHERE organization_id = ?", tenant.organizationId()))
                .hasMessageContaining("append-only");
    }
}
