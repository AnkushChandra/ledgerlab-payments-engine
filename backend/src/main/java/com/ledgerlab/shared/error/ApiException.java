package com.ledgerlab.shared.error;

import java.util.List;
import java.util.Map;

/**
 * A business or request error with a stable {@link ErrorCode}. The message is returned to clients
 * as the problem {@code detail}, so it must never contain secrets or internal identifiers that the
 * caller does not already know.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldError> fieldErrors;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of(), Map.of());
    }

    public ApiException(ErrorCode code, String detail, List<FieldError> fieldErrors, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.properties = Map.copyOf(properties);
    }

    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.NOT_FOUND, resource + " not found");
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public record FieldError(String field, String message) {}
}
