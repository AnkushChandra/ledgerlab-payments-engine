package com.ledgerlab.shared.error;

import com.ledgerlab.shared.web.CorrelationIdFilter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/** Builds RFC 7807 problem bodies with LedgerLab's extension members. */
public final class ProblemFactory {

    private ProblemFactory() {}

    public static ProblemDetail create(
            ErrorCode code, String detail, List<ApiException.FieldError> fieldErrors, Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        if (!fieldErrors.isEmpty()) {
            problem.setProperty("errors", fieldErrors);
        }
        properties.forEach(problem::setProperty);
        return problem;
    }

    public static ProblemDetail create(ErrorCode code, String detail) {
        return create(code, detail, List.of(), Map.of());
    }
}
