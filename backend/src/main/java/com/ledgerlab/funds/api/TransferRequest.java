package com.ledgerlab.funds.api;

import com.ledgerlab.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @Min(Money.MIN_OPERATION_AMOUNT)
                @Max(Money.MAX_OPERATION_AMOUNT)
                @Schema(description = "Amount in USD cents", example = "5000")
                long amountMinor,
        @Size(max = 255) @Schema(example = "Shared expense") String memo) {}
