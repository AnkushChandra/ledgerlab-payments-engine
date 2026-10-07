package com.ledgerlab.auth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ledgerlab")
public record SecurityProperties(Jwt jwt, Security security, Cors cors) {

    public record Jwt(
            @NotBlank(message = "LEDGERLAB_JWT_SECRET must be set")
                    @Size(min = 32, message = "LEDGERLAB_JWT_SECRET must be at least 32 characters")
                    String secret,
            @NotBlank String issuer,
            @NotNull Duration ttl) {

        @Override
        public String toString() {
            return "Jwt[issuer=" + issuer + ", ttl=" + ttl + ", secret=<redacted>]";
        }
    }

    public record Security(@Min(4) @Max(16) int bcryptStrength) {}

    public record Cors(@NotEmpty List<String> allowedOrigins) {}
}
