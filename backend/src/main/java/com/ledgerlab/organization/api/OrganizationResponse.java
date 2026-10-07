package com.ledgerlab.organization.api;

import com.ledgerlab.organization.Organization;
import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(UUID id, String name, String slug, Instant createdAt) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(
                organization.getId(), organization.getName(), organization.getSlug(), organization.getCreatedAt());
    }
}
