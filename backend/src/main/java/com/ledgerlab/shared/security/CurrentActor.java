package com.ledgerlab.shared.security;

import java.util.UUID;

/**
 * The authenticated user acting within exactly one organization. Derived from validated JWT claims
 * and injected into controller methods; services use {@link #organizationId()} for every query.
 */
public record CurrentActor(UUID userId, UUID organizationId, Role role, String email) {}
