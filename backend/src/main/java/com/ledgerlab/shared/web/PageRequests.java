package com.ledgerlab.shared.web;

import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds validated, deterministic page requests. Sorting is restricted to a whitelist that maps
 * public field names to entity properties, and the primary key is always the final tiebreaker.
 */
public final class PageRequests {

    public static final int MAX_SIZE = 100;

    private PageRequests() {}

    public static Pageable of(Integer page, Integer size, String sort, Map<String, String> allowed, String defaultSort) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0) {
            throw invalid("page", "must be greater than or equal to 0");
        }
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw invalid("size", "must be between 1 and " + MAX_SIZE);
        }
        String requested = sort == null || sort.isBlank() ? defaultSort : sort;
        String[] parts = requested.split(",");
        String property = allowed.get(parts[0].trim());
        if (property == null) {
            throw invalid("sort", "must be one of " + allowed.keySet().stream().sorted().toList());
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length > 1) {
            direction = Sort.Direction.fromOptionalString(parts[1].trim())
                    .orElseThrow(() -> invalid("sort", "direction must be asc or desc"));
        }
        Sort order = Sort.by(direction, property).and(Sort.by(direction, "id"));
        return PageRequest.of(pageNumber, pageSize, order);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Request validation failed.",
                List.of(new ApiException.FieldError(field, message)),
                Map.of());
    }
}
