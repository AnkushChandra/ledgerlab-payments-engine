package com.ledgerlab.payment.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ledgerlab.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CapturePaymentRequest(
        @Min(Money.MIN_OPERATION_AMOUNT)
                @Max(Money.MAX_OPERATION_AMOUNT)
                @Schema(description = "Amount in USD cents", example = "4599")
                long amountMinor,
        @Schema(
                        description = "When true, any uncaptured remainder is released back to the customer and "
                                + "the payment becomes CAPTURED.",
                        example = "false")
                Boolean finalCapture) {

    @JsonIgnore
    public boolean isFinal() {
        return Boolean.TRUE.equals(finalCapture);
    }
}
