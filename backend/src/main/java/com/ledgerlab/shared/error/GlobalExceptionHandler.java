package com.ledgerlab.shared.error;

import com.ledgerlab.shared.web.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception to an RFC 7807 problem response. Unexpected exceptions are logged with the
 * correlation id and returned as a generic 500 without stack traces or SQL details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        if (ex.code().status().is5xxServerError()) {
            log.error("API error {}", ex.code(), ex);
        } else {
            log.debug("API error {}: {}", ex.code(), ex.getMessage());
        }
        return respond(ProblemFactory.create(ex.code(), ex.getMessage(), ex.fieldErrors(), ex.properties()));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ProblemDetail> handleAccessDenied(RuntimeException ex) {
        return respond(ProblemFactory.create(ErrorCode.FORBIDDEN, "Your role does not permit this action."));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<ApiException.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ApiException.FieldError(lastPathNode(v.getPropertyPath().toString()), v.getMessage()))
                .sorted(Comparator.comparing(ApiException.FieldError::field))
                .toList();
        return respond(
                ProblemFactory.create(ErrorCode.VALIDATION_FAILED, "Request validation failed.", errors, Map.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        var error = new ApiException.FieldError(ex.getName(), "has an invalid value");
        return respond(ProblemFactory.create(
                ErrorCode.VALIDATION_FAILED, "Request validation failed.", List.of(error), Map.of()));
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> handleLocking(RuntimeException ex) {
        log.warn("Concurrent modification detected: {}", ex.getClass().getSimpleName());
        return respond(ProblemFactory.create(
                ErrorCode.CONCURRENT_MODIFICATION, "The resource was modified concurrently. Retry the request."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return respond(ProblemFactory.create(
                ErrorCode.CONCURRENT_MODIFICATION,
                "The request conflicts with the current state of the resource. Refresh and retry."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ProblemFactory.create(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(validationProblem(ex));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiException.FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiException.FieldError(
                                result.getMethodParameter().getParameterName(), messageOf(error))))
                .toList();
        return asObject(
                ProblemFactory.create(ErrorCode.VALIDATION_FAILED, "Request validation failed.", errors, Map.of()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return asObject(ProblemFactory.create(
                ErrorCode.MALFORMED_REQUEST, "The request body is missing or is not valid JSON for this endpoint."));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (ex instanceof BindException bindException) {
            return asObject(validationProblem(bindException));
        }
        ProblemDetail problem = body instanceof ProblemDetail pd ? pd : ProblemDetail.forStatus(statusCode);
        ErrorCode code = codeForStatus(statusCode);
        problem.setType(URI.create(code.typeUri()));
        problem.setProperty("code", code.name());
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private ProblemDetail validationProblem(BindException ex) {
        List<ApiException.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .sorted(Comparator.comparing(ApiException.FieldError::field))
                .toList();
        List<ApiException.FieldError> globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(error -> new ApiException.FieldError(error.getObjectName(), messageOf(error)))
                .toList();
        var all = new java.util.ArrayList<>(errors);
        all.addAll(globalErrors);
        return ProblemFactory.create(ErrorCode.VALIDATION_FAILED, "Request validation failed.", all, Map.of());
    }

    private static ApiException.FieldError toFieldError(FieldError error) {
        if (error.isBindingFailure()) {
            return new ApiException.FieldError(error.getField(), "has an invalid value");
        }
        return new ApiException.FieldError(error.getField(), messageOf(error));
    }

    private static String messageOf(MessageSourceResolvable error) {
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
    }

    private static String lastPathNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    private static ErrorCode codeForStatus(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.is4xxClientError()) {
            return ErrorCode.MALFORMED_REQUEST;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static ResponseEntity<Object> asObject(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
