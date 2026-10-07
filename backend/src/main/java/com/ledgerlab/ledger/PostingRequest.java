package com.ledgerlab.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A journal to be posted. Construction fails unless the lines form a valid double-entry
 * transaction, so an unbalanced journal cannot even be expressed in application code.
 */
public record PostingRequest(
        UUID organizationId,
        LedgerTransactionType type,
        String description,
        String sourceType,
        UUID sourceId,
        UUID createdBy,
        List<PostingLine> lines) {

    public PostingRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("A journal needs at least two lines");
        }
        long sum = 0;
        for (PostingLine line : lines) {
            sum = Math.addExact(sum, line.amountMinor());
        }
        if (sum != 0) {
            throw new IllegalArgumentException("Journal is unbalanced by " + sum);
        }
    }
}
