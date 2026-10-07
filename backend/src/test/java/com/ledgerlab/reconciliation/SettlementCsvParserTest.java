package com.ledgerlab.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementCsvParserTest {

    private static final String HEADER = "processor_record_id,payment_id,status,amount_minor,currency,settled_at\n";
    private static final String P1 = "6f1c1f2e-3b8a-4c43-9a25-0d6f1f5c2a01";
    private static final String P2 = "6f1c1f2e-3b8a-4c43-9a25-0d6f1f5c2a02";

    @Test
    void parsesValidFile() {
        List<SettlementRow> rows = parse(HEADER
                + "rec-1," + P1 + ",CAPTURED,12000,USD,2026-09-02T10:00:00Z\n"
                + "\"rec-2\"," + P2 + ",refunded,0,USD,2026-09-02T10:05:00+00:00\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).rowNumber()).isEqualTo(2);
        assertThat(rows.get(0).paymentId()).isEqualTo(UUID.fromString(P1));
        assertThat(rows.get(1).status()).isEqualTo("REFUNDED");
        assertThat(rows.get(1).amountMinor()).isZero();
    }

    @Test
    void acceptsUtf8ByteOrderMarkAndBlankLines() {
        List<SettlementRow> rows = parse("\uFEFF" + HEADER + "\n" + "rec-1," + P1 + ",CAPTURED,1,USD,2026-09-02T10:00:00Z\n\n");
        assertThat(rows).hasSize(1);
    }

    @Test
    void rejectsWrongExtension() {
        assertThatThrownBy(() -> SettlementCsvParser.parse("settlement.xlsx", bytes(HEADER)))
                .satisfies(e -> assertCode(e, ErrorCode.INVALID_SETTLEMENT_FILE))
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors().getFirst().field()).isEqualTo("file"));
    }

    @Test
    void rejectsWrongHeader() {
        assertThatThrownBy(() -> parse("id,payment,status,amount,currency,date\nrec-1," + P1
                        + ",CAPTURED,1,USD,2026-09-02T10:00:00Z\n"))
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors().getFirst().field()).isEqualTo("header"));
    }

    @Test
    void rejectsHeaderOnlyAndEmptyFiles() {
        assertThatThrownBy(() -> parse(HEADER)).hasMessageContaining("rejected");
        assertThatThrownBy(() -> SettlementCsvParser.parse("a.csv", new byte[0])).hasMessageContaining("rejected");
    }

    @Test
    void reportsEveryInvalidValueWithRowNumbers() {
        ApiException error = catchApi(HEADER
                + "rec-1,not-a-uuid,CAPTURED,12000,USD,2026-09-02T10:00:00Z\n"
                + "rec-2," + P2 + ",SHIPPED,-5,EUR,yesterday\n"
                + "rec 3," + P1 + ",CAPTURED,1.50,USD,2026-09-02T10:00:00Z\n"
                + "rec-4," + P1 + ",CAPTURED\n");
        assertThat(error.fieldErrors()).extracting(ApiException.FieldError::field)
                .contains("row 2", "row 3", "row 4", "row 5");
        assertThat(error.fieldErrors()).extracting(ApiException.FieldError::message)
                .anyMatch(m -> m.contains("payment_id"))
                .anyMatch(m -> m.contains("status"))
                .anyMatch(m -> m.contains("amount_minor"))
                .anyMatch(m -> m.contains("currency"))
                .anyMatch(m -> m.contains("settled_at"))
                .anyMatch(m -> m.contains("processor_record_id"))
                .anyMatch(m -> m.contains("columns"));
    }

    @Test
    void rejectsDuplicateProcessorRecordIds() {
        ApiException error = catchApi(HEADER
                + "rec-1," + P1 + ",CAPTURED,100,USD,2026-09-02T10:00:00Z\n"
                + "rec-1," + P1 + ",CAPTURED,100,USD,2026-09-02T10:00:00Z\n");
        assertThat(error.fieldErrors()).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("duplicates row 2"));
    }

    @Test
    void rejectsBinaryContent() {
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x41};
        assertThatThrownBy(() -> SettlementCsvParser.parse("x.csv", binary)).hasMessageContaining("rejected");
    }

    private static List<SettlementRow> parse(String csv) {
        return SettlementCsvParser.parse("settlement.csv", bytes(csv));
    }

    private static ApiException catchApi(String csv) {
        try {
            parse(csv);
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected rejection");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertCode(Throwable e, ErrorCode code) {
        assertThat(((ApiException) e).code()).isEqualTo(code);
    }
}
