package com.ledgerlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.support.ApiClient;
import com.ledgerlab.support.ApiClient.Response;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures;
import com.ledgerlab.support.TestFixtures.Tenant;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AuthenticationIT extends IntegrationTest {

    Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
    }

    @Test
    void loginIssuesTokenThatAuthenticatesSubsequentRequests() {
        Response login = login(tenant.operations().email(), TestFixtures.PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(login.body().get("expiresInSeconds").asLong()).isEqualTo(900);
        assertThat(login.body().at("/session/role").asText()).isEqualTo("OPERATIONS");

        Response me = api.get("/api/v1/auth/me", login.body().get("accessToken").asText());
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().at("/user/email").asText()).isEqualTo(tenant.operations().email());
        assertThat(me.body().at("/organization/id").asText()).isEqualTo(tenant.organizationId().toString());

        assertThat(auditActions()).contains("LOGIN_SUCCEEDED");
    }

    @Test
    void wrongPasswordIsRejectedAndAuditedWithoutTheSecret() {
        Response login = login(tenant.viewer().email(), "not-the-password");
        assertThat(login.status()).isEqualTo(401);
        assertThat(login.code()).isEqualTo("INVALID_CREDENTIALS");

        String details = jdbc.queryForObject(
                "SELECT details::text FROM audit_event WHERE organization_id = ? AND action = 'LOGIN_FAILED'",
                String.class,
                tenant.organizationId());
        assertThat(details).contains("BAD_PASSWORD").doesNotContain("not-the-password");
    }

    @Test
    void unknownEmailGetsTheSameResponseAsWrongPassword() {
        Response unknown = login("nobody-" + UUID.randomUUID() + "@example.test", "whatever-password");
        Response wrong = login(tenant.viewer().email(), "whatever-password");
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.body().get("detail")).isEqualTo(wrong.body().get("detail"));
    }

    @Test
    void loginIntoAnOrganizationTheUserDoesNotBelongToIsForbidden() {
        Tenant other = fixtures.newTenant();
        Response login = api.post(
                "/api/v1/auth/login",
                null,
                Map.of("email", tenant.admin().email(), "password", TestFixtures.PASSWORD, "organizationId",
                        other.organizationId()));
        assertThat(login.status()).isEqualTo(403);
    }

    @Test
    void requestsWithoutValidTokenAreUnauthenticated() {
        Response none = api.get("/api/v1/accounts", null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(none.body().get("correlationId").asText()).isNotBlank();

        String token = tenant.admin().token();
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThat(api.get("/api/v1/accounts", tampered).status()).isEqualTo(401);
        assertThat(api.get("/api/v1/accounts", "not-a-jwt").status()).isEqualTo(401);
    }

    @Test
    void expiredTokenAndForeignSignatureAreRejected() {
        String secret = "test-only-secret-0123456789abcdef0123456789";
        assertThat(api.get("/api/v1/accounts", sign(secret, Instant.now().minusSeconds(3600))).status())
                .isEqualTo(401);
        assertThat(api.get("/api/v1/accounts", sign("another-secret-that-is-long-enough-123456", Instant.now()))
                        .status())
                .isEqualTo(401);
        assertThat(api.get("/api/v1/accounts", sign(secret, Instant.now())).status()).isEqualTo(200);
    }

    @Test
    void correlationIdIsEchoedOrGenerated() {
        Response supplied = api.perform(ApiClient.withAuth(MockMvcRequestBuilders.get("/api/v1/accounts"),
                        tenant.viewer().token())
                .header("X-Correlation-Id", "demo-trace-123"));
        assertThat(supplied.header("X-Correlation-Id")).isEqualTo("demo-trace-123");

        Response unsafe = api.perform(ApiClient.withAuth(MockMvcRequestBuilders.get("/api/v1/accounts"),
                        tenant.viewer().token())
                .header("X-Correlation-Id", "bad value\nwith newline"));
        assertThat(unsafe.header("X-Correlation-Id")).isNotEqualTo("bad value\nwith newline").hasSize(36);
    }

    @Test
    void healthEndpointsArePublicAndHideDetails() {
        Response health = api.get("/actuator/health", null);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body().get("status").asText()).isEqualTo("UP");
        assertThat(health.body().get("components")).isNull();
        assertThat(api.get("/actuator/health/readiness", null).status()).isEqualTo(200);
        assertThat(api.get("/actuator/health/liveness", null).status()).isEqualTo(200);
    }

    private String sign(String secret, Instant issuedAt) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var claims = JwtClaimsSet.builder()
                .issuer("ledgerlab")
                .subject(tenant.viewer().actor().userId().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(900))
                .claim("org", tenant.organizationId().toString())
                .claim("role", "VIEWER")
                .claim("email", tenant.viewer().email())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private Response login(String email, String password) {
        return api.post("/api/v1/auth/login", null, Map.of("email", email, "password", password));
    }

    private java.util.List<String> auditActions() {
        return jdbc.queryForList(
                "SELECT action FROM audit_event WHERE organization_id = ?", String.class, tenant.organizationId());
    }
}
