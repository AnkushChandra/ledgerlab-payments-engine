package com.ledgerlab.account.api;

import com.ledgerlab.account.FinancialAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotNull @Schema(example = "CUSTOMER") FinancialAccount.Type type,
        @NotBlank @Size(max = 120) @Schema(example = "Katherine Johnson") String name,
        @NotBlank
                @Size(max = 64)
                @Pattern(regexp = "[A-Za-z0-9._-]+", message = "may contain letters, digits, '.', '_' and '-'")
                @Schema(example = "CUST-1003")
                String reference) {}
