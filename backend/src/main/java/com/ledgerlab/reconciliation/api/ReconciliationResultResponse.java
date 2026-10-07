package com.ledgerlab.reconciliation.api;

import com.ledgerlab.reconciliation.Classification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationResultResponse(
        UUID id,
        int sequenceNumber,
        Classification classification,
        Integer rowNumber,
        String processorRecordId,
        UUID paymentId,
        String expectedStatus,
        String actualStatus,
        Long expectedAmountMinor,
        Long actualAmountMinor,
        String message,
        Instant settledAt) {

    public static ReconciliationResultResponse from(ResultSet rs) throws SQLException {
        Timestamp settledAt = rs.getTimestamp("settled_at");
        return new ReconciliationResultResponse(
                rs.getObject("id", UUID.class),
                rs.getInt("sequence_number"),
                Classification.valueOf(rs.getString("classification")),
                (Integer) rs.getObject("row_number"),
                rs.getString("processor_record_id"),
                rs.getObject("payment_id", UUID.class),
                rs.getString("expected_status"),
                rs.getString("actual_status"),
                (Long) rs.getObject("expected_amount_minor"),
                (Long) rs.getObject("actual_amount_minor"),
                rs.getString("message"),
                settledAt == null ? null : settledAt.toInstant());
    }
}
