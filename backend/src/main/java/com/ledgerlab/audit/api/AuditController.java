package com.ledgerlab.audit.api;

import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditQueryService;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Roles;
import com.ledgerlab.shared.web.PageRequests;
import com.ledgerlab.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit")
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(summary = "Search the organization's append-only audit trail")
    public PageResponse<AuditEventResponse> search(
            CurrentActor actor,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var pageable = PageRequests.of(page, size, sort, Map.of("occurredAt", "occurredAt"), "occurredAt,desc");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Request validation failed.",
                    List.of(new ApiException.FieldError("to", "must be after 'from'")),
                    Map.of());
        }
        boolean ascending = pageable.getSort().stream().anyMatch(order -> order.isAscending());
        var filter = new AuditQueryService.Filter(action, targetType, targetId, actorUserId, from, to, ascending);
        return auditQueryService.search(
                actor.organizationId(), filter, pageable.getPageNumber(), pageable.getPageSize());
    }
}
