package com.ledgerlab.organization;

import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditService;
import com.ledgerlab.organization.api.CreateMembershipRequest;
import com.ledgerlab.organization.api.MembershipResponse;
import com.ledgerlab.organization.api.OrganizationResponse;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Role;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public OrganizationService(
            OrganizationRepository organizations,
            AppUserRepository users,
            MembershipRepository memberships,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            Clock clock) {
        this.organizations = organizations;
        this.users = users;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getOrganization(UUID organizationId) {
        return organizations
                .findById(organizationId)
                .map(OrganizationResponse::from)
                .orElseThrow(() -> ApiException.notFound("Organization"));
    }

    @Transactional(readOnly = true)
    public List<MembershipResponse> listMemberships(UUID organizationId) {
        List<Membership> members = memberships.findByOrganizationIdOrderByCreatedAtAscIdAsc(organizationId);
        Map<UUID, AppUser> usersById = users.findAllById(
                        members.stream().map(Membership::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return members.stream()
                .map(m -> MembershipResponse.from(m, usersById.get(m.getUserId())))
                .toList();
    }

    @Transactional
    public MembershipResponse createMembership(CurrentActor actor, CreateMembershipRequest request) {
        String email = AppUser.normalizeEmail(request.email());
        AppUser user = users.findByEmail(email).orElseGet(() -> createUser(email, request));
        if (memberships
                .findByOrganizationIdAndUserId(actor.organizationId(), user.getId())
                .isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_MEMBERSHIP, "This user is already a member of the organization.");
        }
        Membership membership =
                new Membership(UUID.randomUUID(), actor.organizationId(), user.getId(), request.role(), clock.instant());
        memberships.save(membership);
        audit.record(
                actor,
                AuditAction.MEMBERSHIP_CREATED,
                "MEMBERSHIP",
                membership.getId(),
                Map.of("memberEmail", user.getEmail(), "role", request.role().name()));
        return MembershipResponse.from(membership, user);
    }

    @Transactional
    public MembershipResponse changeRole(CurrentActor actor, UUID membershipId, Role newRole) {
        Membership membership = memberships
                .lockByIdAndOrganizationId(membershipId, actor.organizationId())
                .orElseThrow(() -> ApiException.notFound("Membership"));
        Role previous = membership.getRole();
        if (previous == Role.ADMIN && newRole != Role.ADMIN) {
            // Locking every admin membership serializes concurrent demotions of the last admins.
            List<Membership> admins = memberships.lockByOrganizationIdAndRole(actor.organizationId(), Role.ADMIN);
            if (admins.size() <= 1) {
                throw new ApiException(ErrorCode.LAST_ADMIN, "The organization must keep at least one ADMIN.");
            }
        }
        membership.changeRole(newRole);
        AppUser user = users.findById(membership.getUserId()).orElseThrow();
        if (previous != newRole) {
            audit.record(
                    actor,
                    AuditAction.MEMBERSHIP_ROLE_CHANGED,
                    "MEMBERSHIP",
                    membership.getId(),
                    Map.of("memberEmail", user.getEmail(), "fromRole", previous.name(), "toRole", newRole.name()));
        }
        return MembershipResponse.from(membership, user);
    }

    private AppUser createUser(String email, CreateMembershipRequest request) {
        if (request.password() == null || request.password().length() < 12) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Request validation failed.",
                    List.of(new ApiException.FieldError(
                            "password", "is required for new users and must be at least 12 characters")),
                    Map.of());
        }
        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? email
                : request.displayName().trim();
        AppUser user = new AppUser(
                UUID.randomUUID(), email, passwordEncoder.encode(request.password()), displayName, clock.instant());
        return users.save(user);
    }
}
