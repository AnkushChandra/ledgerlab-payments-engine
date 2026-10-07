package com.ledgerlab.payment.api;

import com.ledgerlab.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AuthorizePaymentRequest(
        @NotNull UUID customerAccountId,
        @NotNull UUID merchantAccountId,
        @Min(Money.MIN_OPERATION_AMOUNT)
                @Max(Money.MAX_OPERATION_AMOUNT)
                @Schema(description = "Amount in USD cents", example = "4599")
                long amountMinor,
        @Size(max = 64) @Schema(example = "ORDER-1042") String reference,
        @Size(max = 255) @Schema(example = "Espresso machine") String description) {}
