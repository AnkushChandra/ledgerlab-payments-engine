package com.ledgerlab.payment.api;

import com.ledgerlab.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record RefundPaymentRequest(
        @Min(Money.MIN_OPERATION_AMOUNT)
                @Max(Money.MAX_OPERATION_AMOUNT)
                @Schema(description = "Amount in USD cents", example = "1000")
                long amountMinor,
        @Size(max = 255) @Schema(example = "Item returned") String reason) {}
