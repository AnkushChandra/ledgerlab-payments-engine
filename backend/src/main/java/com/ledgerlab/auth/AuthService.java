package com.ledgerlab.auth;

import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditService;
import com.ledgerlab.auth.api.LoginRequest;
import com.ledgerlab.auth.api.LoginResponse;
import com.ledgerlab.auth.api.MeResponse;
import com.ledgerlab.organization.AppUser;
import com.ledgerlab.organization.AppUserRepository;
import com.ledgerlab.organization.Membership;
import com.ledgerlab.organization.MembershipRepository;
import com.ledgerlab.organization.Organization;
import com.ledgerlab.organization.OrganizationRepository;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.metrics.LedgerLabMetrics;
import com.ledgerlab.shared.security.CurrentActor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password login. Deliberately not transactional: a failed login must still persist its audit
 * event even though the method ends by throwing.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService audit;
    private final LedgerLabMetrics metrics;
    private final String dummyHash;

    public AuthService(
            AppUserRepository users,
            MembershipRepository memberships,
            OrganizationRepository organizations,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            AuditService audit,
            LedgerLabMetrics metrics) {
        this.users = users;
        this.memberships = memberships;
        this.organizations = organizations;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.audit = audit;
        this.metrics = metrics;
        // Comparing against a dummy hash when the user does not exist keeps response times similar,
        // so login timing does not reveal which emails are registered.
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest request) {
        String email = AppUser.normalizeEmail(request.email());
        Optional<AppUser> found = users.findByEmail(email);
        boolean passwordMatches =
                passwordEncoder.matches(request.password(), found.map(AppUser::getPasswordHash).orElse(dummyHash));

        if (found.isEmpty() || !passwordMatches || !found.get().isActive()) {
            String reason = found.isEmpty() ? "UNKNOWN_USER" : !passwordMatches ? "BAD_PASSWORD" : "USER_DISABLED";
            recordFailure(found, email, reason);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
        }

        AppUser user = found.get();
        List<Membership> userMemberships = memberships.findByUserIdOrderByCreatedAtAscIdAsc(user.getId());
        if (userMemberships.isEmpty()) {
            recordFailure(found, email, "NO_MEMBERSHIP");
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
        }
        Membership membership = request.organizationId() == null
                ? userMemberships.getFirst()
                : userMemberships.stream()
                        .filter(m -> m.getOrganizationId().equals(request.organizationId()))
                        .findFirst()
                        .orElseThrow(() -> {
                            recordFailure(found, email, "NOT_A_MEMBER");
                            return new ApiException(
                                    ErrorCode.FORBIDDEN, "You are not a member of the requested organization.");
                        });

        TokenService.IssuedToken token = tokenService.issue(
                user.getId(), membership.getOrganizationId(), membership.getRole(), user.getEmail());
        CurrentActor actor =
                new CurrentActor(user.getId(), membership.getOrganizationId(), membership.getRole(), user.getEmail());
        audit.record(actor, AuditAction.LOGIN_SUCCEEDED, "USER", user.getId(), Map.of("role", membership.getRole()));
        metrics.authentication("success");
        log.info("Login succeeded for user {} in organization {}", user.getId(), membership.getOrganizationId());
        return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds(), describe(actor));
    }

    public MeResponse describe(CurrentActor actor) {
        AppUser user = users.findById(actor.userId()).orElseThrow(() -> ApiException.notFound("User"));
        List<Membership> userMemberships = memberships.findByUserIdOrderByCreatedAtAscIdAsc(user.getId());
        Map<UUID, Organization> orgs =
                organizations
                        .findAllById(userMemberships.stream()
                                .map(Membership::getOrganizationId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(Organization::getId, Function.identity()));
        Organization current = orgs.get(actor.organizationId());
        if (current == null) {
            throw ApiException.notFound("Organization");
        }
        return new MeResponse(
                new MeResponse.UserSummary(user.getId(), user.getEmail(), user.getDisplayName()),
                new MeResponse.OrganizationSummary(current.getId(), current.getName(), current.getSlug()),
                actor.role(),
                userMemberships.stream()
                        .map(m -> new MeResponse.MembershipSummary(
                                m.getOrganizationId(),
                                orgs.get(m.getOrganizationId()).getName(),
                                m.getRole()))
                        .toList());
    }

    private void recordFailure(Optional<AppUser> user, String email, String reason) {
        UUID organizationId = user.flatMap(u -> memberships.findByUserIdOrderByCreatedAtAscIdAsc(u.getId()).stream()
                        .findFirst())
                .map(Membership::getOrganizationId)
                .orElse(null);
        audit.record(
                organizationId,
                user.map(AppUser::getId).orElse(null),
                email,
                AuditAction.LOGIN_FAILED,
                "USER",
                user.map(AppUser::getId).orElse(null),
                Map.of("reason", reason));
        metrics.authentication("failure");
        log.info("Login failed: {}", reason);
    }
}
