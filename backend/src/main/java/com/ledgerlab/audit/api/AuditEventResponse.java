package com.ledgerlab.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerlab.audit.AuditAction;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        AuditAction action,
        String targetType,
        String targetId,
        String correlationId,
        JsonNode details,
        Instant occurredAt) {}
