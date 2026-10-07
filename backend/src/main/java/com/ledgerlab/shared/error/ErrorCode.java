package com.ledgerlab.shared.error;

import org.springframework.http.HttpStatus;

/** Stable, client-facing error codes. The HTTP status for each code is fixed here and nowhere else. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency key required"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Invalid idempotency key"),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),

    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),

    INVALID_PAYMENT_STATE(HttpStatus.CONFLICT, "Invalid payment state"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused"),
    DUPLICATE_SETTLEMENT_FILE(HttpStatus.CONFLICT, "Duplicate settlement file"),
    DISPUTE_ALREADY_OPEN(HttpStatus.CONFLICT, "Dispute already open"),
    INVALID_DISPUTE_STATE(HttpStatus.CONFLICT, "Invalid dispute state"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
    DUPLICATE_REFERENCE(HttpStatus.CONFLICT, "Duplicate reference"),
    DUPLICATE_MEMBERSHIP(HttpStatus.CONFLICT, "Duplicate membership"),

    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"),
    MERCHANT_INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Merchant has insufficient funds"),
    CAPTURE_EXCEEDS_AUTHORIZATION(HttpStatus.UNPROCESSABLE_ENTITY, "Capture exceeds authorization"),
    REFUND_EXCEEDS_CAPTURED(HttpStatus.UNPROCESSABLE_ENTITY, "Refund exceeds captured amount"),
    DISPUTE_EXCEEDS_REFUNDABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Dispute exceeds refundable amount"),
    ACCOUNT_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Account not active"),
    INVALID_ACCOUNT_TYPE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid account type"),
    SAME_ACCOUNT_TRANSFER(HttpStatus.UNPROCESSABLE_ENTITY, "Source and destination must differ"),
    ACCOUNT_HAS_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Account has a non-zero balance"),
    INVALID_STATUS_CHANGE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid status change"),
    LAST_ADMIN(HttpStatus.UNPROCESSABLE_ENTITY, "Organization must keep an administrator"),
    INVALID_SETTLEMENT_FILE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid settlement file"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return "https://ledgerlab.dev/problems/" + name().toLowerCase().replace('_', '-');
    }
}
