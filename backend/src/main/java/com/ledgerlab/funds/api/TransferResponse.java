package com.ledgerlab.funds.api;

import com.ledgerlab.funds.Transfer;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountMinor,
        String currency,
        String memo,
        UUID ledgerTransactionId,
        UUID createdBy,
        Instant createdAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                transfer.getAmountMinor(),
                transfer.getCurrency(),
                transfer.getMemo(),
                transfer.getLedgerTransactionId(),
                transfer.getCreatedBy(),
                transfer.getCreatedAt());
    }
}
