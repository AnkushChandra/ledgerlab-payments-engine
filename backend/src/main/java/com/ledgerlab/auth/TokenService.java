package com.ledgerlab.auth;

import com.ledgerlab.shared.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    public static final String ORGANIZATION_CLAIM = "org";
    public static final String ROLE_CLAIM = "role";
    public static final String EMAIL_CLAIM = "email";

    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(UUID userId, UUID organizationId, Role role, String email) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.jwt().ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(ORGANIZATION_CLAIM, organizationId.toString())
                .claim(ROLE_CLAIM, role.name())
                .claim(EMAIL_CLAIM, email)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.jwt().ttl().toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
        @Override
        public String toString() {
            return "IssuedToken[value=<redacted>, expiresInSeconds=" + expiresInSeconds + "]";
        }
    }
}
