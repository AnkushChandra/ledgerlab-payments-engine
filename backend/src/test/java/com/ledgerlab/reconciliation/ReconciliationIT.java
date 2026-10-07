package com.ledgerlab.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.support.ApiClient;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class ReconciliationIT extends IntegrationTest {

    private static final String HEADER = "processor_record_id,payment_id,status,amount_minor,currency,settled_at\n";

    Tenant tenant;
    String ops;
    String captured;
    String partiallyRefunded;
    String refunded;
    String unreported;
    String today;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        ops = tenant.operations().token();
        AccountResponse customer = fixtures.fundedCustomer(tenant, "Ada", 200_000);
        AccountResponse merchant = fixtures.merchant(tenant, "Shop");
        captured = capturedPayment(customer, merchant, 12_000);
        partiallyRefunded = capturedPayment(customer, merchant, 25_000);
        refund(partiallyRefunded, 5_000);
        refunded = capturedPayment(customer, merchant, 40_000);
        refund(refunded, 40_000);
        unreported = capturedPayment(customer, merchant, 9_900);
        today = LocalDate.now(ZoneOffset.UTC).toString();
    }

    @Test
    void perfectFileReconcilesAsMatched() {
        String csv = HEADER
                + line("r1", captured, "CAPTURED", 12_000)
                + line("r2", partiallyRefunded, "PARTIALLY_REFUNDED", 20_000)
                + line("r3", refunded, "REFUNDED", 0)
                + line("r4", unreported, "CAPTURED", 9_900);

        Response upload = upload("matched.csv", csv, TestFixtures.newKey());

        assertThat(upload.status()).isEqualTo(201);
        assertThat(upload.body().at("/counts/MATCHED").asInt()).isEqualTo(4);
        assertThat(upload.body().get("exceptionCount").asInt()).isZero();
        assertThat(upload.body().get("recordCount").asInt()).isEqualTo(4);
    }

    @Test
    void mismatchedFileIdentifiesEveryExceptionType() {
        String csv = HEADER
                + line("r1", captured, "CAPTURED", 12_000)
                + line("r2", partiallyRefunded, "PARTIALLY_REFUNDED", 25_000)
                + line("r3", refunded, "CAPTURED", 40_000)
                + line("r4", UUID.randomUUID().toString(), "CAPTURED", 5_000)
                + line("r5", captured, "CAPTURED", 12_000);

        Response upload = upload("mismatch.csv", csv, TestFixtures.newKey());
        JsonNode counts = upload.body().get("counts");
        assertThat(counts.get("MATCHED").asInt()).isEqualTo(1);
        assertThat(counts.get("AMOUNT_MISMATCH").asInt()).isEqualTo(1);
        assertThat(counts.get("STATUS_MISMATCH").asInt()).isEqualTo(1);
        assertThat(counts.get("MISSING_INTERNAL").asInt()).isEqualTo(1);
        assertThat(counts.get("DUPLICATE_EXTERNAL").asInt()).isEqualTo(1);
        assertThat(counts.get("MISSING_EXTERNAL").asInt()).isEqualTo(1);
        assertThat(upload.body().get("exceptionCount").asInt()).isEqualTo(5);

        String batchId = upload.body().get("id").asText();
        Response missingExternal = api.get(
                "/api/v1/settlement-batches/" + batchId + "/results?classification=MISSING_EXTERNAL", ops);
        assertThat(missingExternal.body().get("totalItems").asInt()).isEqualTo(1);
        assertThat(missingExternal.body().at("/items/0/paymentId").asText()).isEqualTo(unreported);
        assertThat(missingExternal.body().at("/items/0/rowNumber").isNull()).isTrue();

        Response amount = api.get(
                "/api/v1/settlement-batches/" + batchId + "/results?classification=AMOUNT_MISMATCH", ops);
        assertThat(amount.body().at("/items/0/expectedAmountMinor").asLong()).isEqualTo(20_000);
        assertThat(amount.body().at("/items/0/actualAmountMinor").asLong()).isEqualTo(25_000);
        assertThat(amount.body().at("/items/0/processorRecordId").asText()).isEqualTo("r2");

        Response all = api.get("/api/v1/settlement-batches/" + batchId + "/results", ops);
        assertThat(all.body().get("totalItems").asInt()).isEqualTo(6);

        Response csvExport = api.get("/api/v1/settlement-batches/" + batchId + "/results.csv", tenant.viewer().token());
        assertThat(csvExport.status()).isEqualTo(200);
        assertThat(csvExport.header("Content-Disposition")).contains("attachment");
        String exported = csvExport.text();
        assertThat(exported.lines()).hasSize(7);
        assertThat(exported.lines().findFirst().orElseThrow()).startsWith("sequence,classification");
        assertThat(exported).contains("DUPLICATE_EXTERNAL", "MISSING_EXTERNAL", "STATUS_MISMATCH");

        assertThat(jdbc.queryForList(
                        "SELECT action FROM audit_event WHERE organization_id = ? AND target_id = ?",
                        String.class,
                        tenant.organizationId(),
                        batchId))
                .containsExactlyInAnyOrder("SETTLEMENT_IMPORTED", "RECONCILIATION_COMPLETED");
    }

    @Test
    void malformedFileIsRejectedAndNothingIsPersisted() {
        String csv = HEADER + "r1,not-a-uuid,CAPTURED,12000,USD,2026-09-02T10:00:00Z\n"
                + line("r1", captured, "CAPTURED", 12_000);
        Response upload = upload("bad.csv", csv, TestFixtures.newKey());

        assertThat(upload.status()).isEqualTo(422);
        assertThat(upload.code()).isEqualTo("INVALID_SETTLEMENT_FILE");
        assertThat(upload.body().get("errors").findValuesAsText("field")).contains("row 2", "row 3");
        assertThat(batchCount()).isZero();
    }

    @Test
    void nonCsvUploadIsRejected() {
        Response upload = api.perform(ApiClient.withAuth(
                        MockMvcRequestBuilders.multipart("/api/v1/settlement-batches")
                                .file(new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[] {1, 2}))
                                .param("periodStart", today)
                                .param("periodEnd", today),
                        ops)
                .header("Idempotency-Key", TestFixtures.newKey()));
        assertThat(upload.status()).isEqualTo(422);
    }

    @Test
    void importsAreIdempotent() {
        String csv = HEADER + line("r1", captured, "CAPTURED", 12_000);
        String key = TestFixtures.newKey();

        Response first = upload("s.csv", csv, key);
        Response replay = upload("s.csv", csv, key);
        Response sameContentNewKey = upload("renamed.csv", csv, TestFixtures.newKey());

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().get("id")).isEqualTo(first.body().get("id"));
        assertThat(sameContentNewKey.status()).isEqualTo(409);
        assertThat(sameContentNewKey.code()).isEqualTo("DUPLICATE_SETTLEMENT_FILE");
        assertThat(sameContentNewKey.body().get("existingBatchId")).isEqualTo(first.body().get("id"));
        assertThat(batchCount()).isEqualTo(1);
    }

    @Test
    void periodIsValidated() {
        Response reversed = api.perform(ApiClient.withAuth(
                        MockMvcRequestBuilders.multipart("/api/v1/settlement-batches")
                                .file(new MockMultipartFile("file", "s.csv", "text/csv",
                                        (HEADER + line("r1", captured, "CAPTURED", 12_000))
                                                .getBytes(StandardCharsets.UTF_8)))
                                .param("periodStart", "2026-09-10")
                                .param("periodEnd", "2026-09-01"),
                        ops)
                .header("Idempotency-Key", TestFixtures.newKey()));
        assertThat(reversed.status()).isEqualTo(400);
    }

    @Test
    void batchesAreTenantScopedAndViewerCannotUpload() {
        String batchId = upload("s.csv", HEADER + line("r1", captured, "CAPTURED", 12_000), TestFixtures.newKey())
                .body().get("id").asText();
        Tenant other = fixtures.newTenant();
        assertThat(api.get("/api/v1/settlement-batches/" + batchId, other.admin().token()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/settlement-batches/" + batchId + "/results", other.admin().token()).status())
                .isEqualTo(404);
        assertThat(api.get("/api/v1/settlement-batches/" + batchId + "/results.csv", other.admin().token()).status())
                .isEqualTo(404);
        assertThat(api.get("/api/v1/settlement-batches", other.admin().token()).body().get("totalItems").asInt())
                .isZero();

        Response viewerUpload = api.perform(ApiClient.withAuth(
                        MockMvcRequestBuilders.multipart("/api/v1/settlement-batches")
                                .file(new MockMultipartFile("file", "v.csv", "text/csv",
                                        (HEADER + line("rv", captured, "CAPTURED", 1)).getBytes(StandardCharsets.UTF_8)))
                                .param("periodStart", today)
                                .param("periodEnd", today),
                        tenant.viewer().token())
                .header("Idempotency-Key", TestFixtures.newKey()));
        assertThat(viewerUpload.status()).isEqualTo(403);
    }

    private Response upload(String fileName, String csv, String key) {
        return api.perform(ApiClient.withAuth(
                        MockMvcRequestBuilders.multipart("/api/v1/settlement-batches")
                                .file(new MockMultipartFile("file", fileName, "text/csv",
                                        csv.getBytes(StandardCharsets.UTF_8)))
                                .param("periodStart", today)
                                .param("periodEnd", today),
                        ops)
                .header("Idempotency-Key", key));
    }

    private String line(String recordId, String paymentId, String status, long amount) {
        return String.join(",", recordId, paymentId, status, Long.toString(amount), "USD", today + "T23:00:00Z")
                + "\n";
    }

    private String capturedPayment(AccountResponse customer, AccountResponse merchant, long amount) {
        String id = api.post("/api/v1/payments", ops,
                        Map.of("customerAccountId", customer.id(), "merchantAccountId", merchant.id(),
                                "amountMinor", amount),
                        TestFixtures.newKey())
                .body().get("id").asText();
        api.post("/api/v1/payments/" + id + "/captures", ops, Map.of("amountMinor", amount), TestFixtures.newKey());
        return id;
    }

    private void refund(String paymentId, long amount) {
        api.post("/api/v1/payments/" + paymentId + "/refunds", ops, Map.of("amountMinor", amount),
                TestFixtures.newKey());
    }

    private long batchCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM settlement_batch WHERE organization_id = ?", Long.class, tenant.organizationId());
    }
}
