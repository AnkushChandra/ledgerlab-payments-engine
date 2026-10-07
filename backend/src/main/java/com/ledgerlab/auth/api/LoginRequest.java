package com.ledgerlab.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record LoginRequest(
        @NotBlank @Size(max = 254) @Schema(example = "ops@acme.test") String email,
        @NotBlank @Size(max = 128) @Schema(example = "LedgerLab!2026") String password,
        @Schema(description = "Optional; defaults to the user's first membership") UUID organizationId) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=<redacted>, organizationId=" + organizationId + "]";
    }
}
