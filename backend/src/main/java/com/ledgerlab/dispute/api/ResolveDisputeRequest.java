package com.ledgerlab.dispute.api;

import com.ledgerlab.dispute.Dispute;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveDisputeRequest(
        @NotNull
                @Schema(
                        description = "WON: merchant keeps the funds. LOST: funds are charged back to the customer.",
                        example = "LOST")
                Dispute.Outcome outcome,
        @Size(max = 500) @Schema(example = "Carrier confirmed non-delivery") String note) {}
