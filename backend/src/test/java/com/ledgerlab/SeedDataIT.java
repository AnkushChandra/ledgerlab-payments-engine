package com.ledgerlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerlab.ledger.LedgerService;
import com.ledgerlab.support.ApiClient;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Verifies the dev/e2e seed: it migrates cleanly, its journals satisfy every ledger invariant, demo
 * credentials work, and the committed sample settlement files produce the documented results.
 */
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class SeedDataIT extends IntegrationTest {

    private static final UUID ACME = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Autowired
    LedgerService ledgerService;

    @Test
    void seedSatisfiesLedgerInvariantsAndDemoFlowsWork() throws Exception {
        assertThat(ledgerService.integrity(ACME).healthy()).isTrue();
        assertThat(ledgerService.integrity(GLOBEX).healthy()).isTrue();
        assertThat(ledgerService.integrity(ACME).clearingBalanceMinor()).isEqualTo(800_000);

        String ops = login("ops@acme.test");
        JsonNode ada = api.get("/api/v1/accounts/30000000-0000-4000-8000-000000000001", ops).body();
        assertThat(ada.get("availableMinor").asLong()).isEqualTo(458_100);
        JsonNode grace = api.get("/api/v1/accounts/30000000-0000-4000-8000-000000000002", ops).body();
        assertThat(grace.get("heldMinor").asLong()).isEqualTo(8_000);

        String viewer = login("viewer@acme.test");
        assertThat(api.get("/api/v1/payments", viewer).body().get("totalItems").asInt()).isEqualTo(8);
        String globex = login("admin@globex.test");
        assertThat(api.get("/api/v1/payments", globex).body().get("totalItems").asInt()).isEqualTo(1);

        JsonNode matched = upload(ops, "settlement-matched.csv").body();
        assertThat(matched.at("/counts/MATCHED").asInt()).isEqualTo(5);
        assertThat(matched.get("exceptionCount").asInt()).isZero();

        JsonNode mismatched = upload(ops, "settlement-mismatches.csv").body();
        assertThat(mismatched.get("counts"))
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of(
                        "MATCHED", 2,
                        "MISSING_INTERNAL", 1,
                        "MISSING_EXTERNAL", 1,
                        "AMOUNT_MISMATCH", 1,
                        "STATUS_MISMATCH", 1,
                        "DUPLICATE_EXTERNAL", 1)));

        Response malformed = upload(ops, "settlement-malformed.csv");
        assertThat(malformed.status()).isEqualTo(422);
        assertThat(malformed.body().get("errors").findValuesAsText("field"))
                .contains("row 2", "row 3", "row 4", "row 5");
    }

    private String login(String email) {
        Response response =
                api.post("/api/v1/auth/login", null, Map.of("email", email, "password", "LedgerLab!2026"));
        assertThat(response.status()).as(email).isEqualTo(200);
        return response.body().get("accessToken").asText();
    }

    private Response upload(String token, String sample) throws Exception {
        byte[] content = Files.readAllBytes(Path.of("..", "samples", sample));
        return api.perform(ApiClient.withAuth(
                        MockMvcRequestBuilders.multipart("/api/v1/settlement-batches")
                                .file(new MockMultipartFile("file", sample, "text/csv", content))
                                .param("periodStart", "2026-09-01")
                                .param("periodEnd", "2026-09-03"),
                        token)
                .header("Idempotency-Key", TestFixtures.newKey()));
    }
}
