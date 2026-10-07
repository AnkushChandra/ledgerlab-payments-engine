package com.ledgerlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Role-based authorization: VIEWER is read-only, OPERATIONS cannot administer, ADMIN can. */
class AuthorizationIT extends IntegrationTest {

    Tenant tenant;
    AccountResponse customer;
    AccountResponse merchant;
    String paymentId;
    String disputeId;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        customer = fixtures.fundedCustomer(tenant, "Ada", 50_000);
        merchant = fixtures.merchant(tenant, "Shop");
        String ops = tenant.operations().token();
        paymentId = api.post("/api/v1/payments", ops,
                        Map.of("customerAccountId", customer.id(), "merchantAccountId", merchant.id(),
                                "amountMinor", 5_000),
                        TestFixtures.newKey())
                .body().get("id").asText();
        String captured = api.post("/api/v1/payments", ops,
                        Map.of("customerAccountId", customer.id(), "merchantAccountId", merchant.id(),
                                "amountMinor", 5_000),
                        TestFixtures.newKey())
                .body().get("id").asText();
        api.post("/api/v1/payments/" + captured + "/captures", ops, Map.of("amountMinor", 5_000), TestFixtures.newKey());
        disputeId = api.post("/api/v1/payments/" + captured + "/disputes", ops, Map.of("reason", "Fraud"),
                        TestFixtures.newKey())
                .body().get("id").asText();
    }

    record Mutation(String path, Object body) {}

    private List<Mutation> financialMutations() {
        return List.of(
                new Mutation("/api/v1/accounts", Map.of("type", "CUSTOMER", "name", "X", "reference", "X-1")),
                new Mutation("/api/v1/deposits", Map.of("accountId", customer.id(), "amountMinor", 100)),
                new Mutation("/api/v1/transfers", Map.of("sourceAccountId", customer.id(),
                        "destinationAccountId", merchant.id(), "amountMinor", 100)),
                new Mutation("/api/v1/payments", Map.of("customerAccountId", customer.id(),
                        "merchantAccountId", merchant.id(), "amountMinor", 100)),
                new Mutation("/api/v1/payments/" + paymentId + "/captures", Map.of("amountMinor", 100)),
                new Mutation("/api/v1/payments/" + paymentId + "/void", Map.of()),
                new Mutation("/api/v1/payments/" + paymentId + "/refunds", Map.of("amountMinor", 100)),
                new Mutation("/api/v1/payments/" + paymentId + "/disputes", Map.of("reason", "x")),
                new Mutation("/api/v1/disputes/" + disputeId + "/resolution", Map.of("outcome", "WON")));
    }

    @Test
    void viewerCannotPerformAnyFinancialMutation() {
        long journalsBefore = journalCount();
        for (Mutation mutation : financialMutations()) {
            Response response =
                    api.post(mutation.path(), tenant.viewer().token(), mutation.body(), TestFixtures.newKey());
            assertThat(response.status()).as(mutation.path()).isEqualTo(403);
            assertThat(response.code()).as(mutation.path()).isEqualTo("FORBIDDEN");
        }
        assertThat(journalCount()).isEqualTo(journalsBefore);
    }

    @Test
    void viewerCanReadButNotSeeAuditTrail() {
        String viewer = tenant.viewer().token();
        assertThat(api.get("/api/v1/accounts", viewer).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/payments/" + paymentId, viewer).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/disputes", viewer).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/ledger/integrity", viewer).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/audit-events", viewer).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/audit-events", tenant.operations().token()).status()).isEqualTo(200);
    }

    @Test
    void operationsCannotPerformAdministrativeChanges() {
        String ops = tenant.operations().token();
        assertThat(api.patch("/api/v1/accounts/" + customer.id(), ops, Map.of("status", "FROZEN")).status())
                .isEqualTo(403);
        assertThat(api.get("/api/v1/organization/memberships", ops).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/organization/memberships", ops,
                                Map.of("email", "x@example.test", "password", "long-enough-password", "role", "ADMIN"))
                        .status())
                .isEqualTo(403);
    }

    @Test
    void adminManagesMembershipsAndCannotRemoveLastAdmin() {
        String admin = tenant.admin().token();
        Response created = api.post("/api/v1/organization/memberships", admin,
                Map.of("email", "analyst-" + tenant.organizationId() + "@example.test", "password",
                        "a-long-enough-password", "role", "VIEWER"));
        assertThat(created.status()).isEqualTo(201);

        Response promoted = api.patch(
                "/api/v1/organization/memberships/" + created.body().get("id").asText(), admin,
                Map.of("role", "OPERATIONS"));
        assertThat(promoted.body().get("role").asText()).isEqualTo("OPERATIONS");

        String adminMembership = jdbc.queryForObject(
                "SELECT id::text FROM organization_membership WHERE organization_id = ? AND role = 'ADMIN'",
                String.class,
                tenant.organizationId());
        Response demoteLast = api.patch(
                "/api/v1/organization/memberships/" + adminMembership, admin, Map.of("role", "VIEWER"));
        assertThat(demoteLast.status()).isEqualTo(422);
        assertThat(demoteLast.code()).isEqualTo("LAST_ADMIN");

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_event WHERE organization_id = ?", String.class, tenant.organizationId());
        assertThat(actions).contains("MEMBERSHIP_CREATED", "MEMBERSHIP_ROLE_CHANGED");
    }

    @Test
    void adminCanFreezeAccountsAndFrozenAccountsCannotMoveFunds() {
        Response frozen = api.patch("/api/v1/accounts/" + customer.id(), tenant.admin().token(),
                Map.of("status", "FROZEN"));
        assertThat(frozen.body().get("status").asText()).isEqualTo("FROZEN");
        Response transfer = api.post("/api/v1/transfers", tenant.operations().token(),
                Map.of("sourceAccountId", customer.id(), "destinationAccountId", merchant.id(), "amountMinor", 100),
                TestFixtures.newKey());
        assertThat(transfer.status()).isEqualTo(422);
        assertThat(transfer.code()).isEqualTo("ACCOUNT_NOT_ACTIVE");

        Response close = api.patch("/api/v1/accounts/" + customer.id(), tenant.admin().token(),
                Map.of("status", "CLOSED"));
        assertThat(close.status()).isEqualTo(422);
        assertThat(close.code()).isEqualTo("ACCOUNT_HAS_BALANCE");
    }

    private long journalCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transaction WHERE organization_id = ?", Long.class, tenant.organizationId());
    }
}
