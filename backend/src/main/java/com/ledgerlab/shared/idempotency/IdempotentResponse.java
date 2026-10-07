package com.ledgerlab.shared.idempotency;

import java.util.UUID;

/** What an operation produced; stored so that retries can be answered identically. */
public record IdempotentResponse<T>(int status, T body, UUID resourceId) {

    public static <T> IdempotentResponse<T> created(T body, UUID resourceId) {
        return new IdempotentResponse<>(201, body, resourceId);
    }

    public static <T> IdempotentResponse<T> ok(T body, UUID resourceId) {
        return new IdempotentResponse<>(200, body, resourceId);
    }
}
