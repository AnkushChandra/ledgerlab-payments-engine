package com.ledgerlab.auth.api;

import com.ledgerlab.shared.security.Role;
import java.util.List;
import java.util.UUID;

public record MeResponse(
        UserSummary user, OrganizationSummary organization, Role role, List<MembershipSummary> memberships) {

    public record UserSummary(UUID id, String email, String displayName) {}

    public record OrganizationSummary(UUID id, String name, String slug) {}

    public record MembershipSummary(UUID organizationId, String organizationName, Role role) {}
}
