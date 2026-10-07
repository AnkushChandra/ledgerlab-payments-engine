package com.ledgerlab.auth;

import com.ledgerlab.shared.security.Role;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rejects tokens that are correctly signed but lack well-formed LedgerLab claims. */
class RequiredClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "Missing or malformed claims", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
            UUID.fromString(jwt.getClaimAsString(TokenService.ORGANIZATION_CLAIM));
            Role.valueOf(jwt.getClaimAsString(TokenService.ROLE_CLAIM));
            if (jwt.getExpiresAt() == null) {
                return OAuth2TokenValidatorResult.failure(INVALID);
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
    }
}
