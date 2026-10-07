package com.ledgerlab.organization.api;

import com.ledgerlab.shared.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateMembershipRequest(
        @NotBlank @Email @Size(max = 254) @Schema(example = "new.analyst@acme.test") String email,
        @Size(max = 120) @Schema(example = "New Analyst") String displayName,
        @Size(max = 128)
                @Schema(
                        description = "Required when the email does not belong to an existing user (min 12 chars).",
                        example = "a-long-unique-passphrase")
                String password,
        @NotNull Role role) {

    @Override
    public String toString() {
        return "CreateMembershipRequest[email=" + email + ", role=" + role + "]";
    }
}
