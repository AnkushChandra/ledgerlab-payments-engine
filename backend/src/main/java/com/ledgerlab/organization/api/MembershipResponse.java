package com.ledgerlab.organization.api;

import com.ledgerlab.organization.AppUser;
import com.ledgerlab.organization.Membership;
import com.ledgerlab.shared.security.Role;
import java.time.Instant;
import java.util.UUID;

public record MembershipResponse(
        UUID id, UUID userId, String email, String displayName, Role role, Instant createdAt) {

    public static MembershipResponse from(Membership membership, AppUser user) {
        return new MembershipResponse(
                membership.getId(),
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                membership.getRole(),
                membership.getCreatedAt());
    }
}
