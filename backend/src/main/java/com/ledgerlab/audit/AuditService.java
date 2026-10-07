package com.ledgerlab.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.web.CorrelationIdFilter;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Appends audit events. Business events are written inside the caller's transaction, so an
 * operation that rolls back leaves no audit record claiming it happened. Details must contain only
 * safe values: never passwords, tokens or full request bodies.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void record(CurrentActor actor, AuditAction action, String targetType, UUID targetId, Map<String, ?> details) {
        record(actor.organizationId(), actor.userId(), actor.email(), action, targetType, targetId, details);
    }

    public void record(
            UUID organizationId,
            UUID actorUserId,
            String actorEmail,
            AuditAction action,
            String targetType,
            UUID targetId,
            Map<String, ?> details) {
        jdbc.update(
                """
                INSERT INTO audit_event (id, organization_id, actor_user_id, actor_email, action, target_type,
                                         target_id, correlation_id, details, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                actorEmail,
                action.name(),
                targetType,
                targetId == null ? null : targetId.toString(),
                CorrelationIdFilter.current(),
                toJson(details),
                Timestamp.from(clock.instant()));
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit details are not serializable", e);
        }
    }
}
