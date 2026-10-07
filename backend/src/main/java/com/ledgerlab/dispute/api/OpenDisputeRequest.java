package com.ledgerlab.dispute.api;

import com.ledgerlab.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OpenDisputeRequest(
        @Min(Money.MIN_OPERATION_AMOUNT)
                @Max(Money.MAX_OPERATION_AMOUNT)
                @Schema(description = "Defaults to the full refundable amount", example = "3000")
                Long amountMinor,
        @NotBlank @Size(max = 255) @Schema(example = "Customer reports goods not received") String reason) {}
