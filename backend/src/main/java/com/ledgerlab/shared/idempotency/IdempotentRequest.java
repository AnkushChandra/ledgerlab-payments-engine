package com.ledgerlab.shared.idempotency;

import java.util.UUID;

/**
 * Identifies an idempotent operation.
 *
 * @param organizationId tenant that owns the key
 * @param operation stable operation name, e.g. {@code PAYMENT_CAPTURE}
 * @param key the client's {@code Idempotency-Key}
 * @param scope path resource the operation targets (empty string when none)
 * @param payload request body (or a safe digest of it) used for the fingerprint
 */
public record IdempotentRequest(UUID organizationId, String operation, String key, String scope, Object payload) {

    public static IdempotentRequest of(UUID organizationId, String operation, String key, Object payload) {
        return new IdempotentRequest(organizationId, operation, key, "", payload);
    }

    public static IdempotentRequest of(UUID organizationId, String operation, String key, UUID scope, Object payload) {
        return new IdempotentRequest(organizationId, operation, key, scope.toString(), payload);
    }
}
