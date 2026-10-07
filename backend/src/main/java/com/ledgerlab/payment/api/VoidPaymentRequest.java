package com.ledgerlab.payment.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record VoidPaymentRequest(@Size(max = 255) @Schema(example = "Order cancelled") String reason) {}
