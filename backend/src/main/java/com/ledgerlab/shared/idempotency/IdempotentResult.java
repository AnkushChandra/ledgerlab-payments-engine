package com.ledgerlab.shared.idempotency;

import org.springframework.http.ResponseEntity;

/** Result returned to the controller: either freshly executed or replayed from storage. */
public record IdempotentResult<T>(int status, T body, boolean replayed) {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    public ResponseEntity<T> toResponseEntity() {
        return ResponseEntity.status(status)
                .header(REPLAYED_HEADER, Boolean.toString(replayed))
                .body(body);
    }
}
