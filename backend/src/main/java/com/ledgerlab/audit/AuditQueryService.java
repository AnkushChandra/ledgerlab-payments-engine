package com.ledgerlab.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerlab.audit.api.AuditEventResponse;
import com.ledgerlab.shared.web.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Filter(
            AuditAction action,
            String targetType,
            String targetId,
            UUID actorUserId,
            Instant from,
            Instant to,
            boolean ascending) {}

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> search(UUID organizationId, Filter filter, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE organization_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        if (filter.action() != null) {
            where.append(" AND action = ?");
            args.add(filter.action().name());
        }
        if (filter.targetType() != null) {
            where.append(" AND target_type = ?");
            args.add(filter.targetType());
        }
        if (filter.targetId() != null) {
            where.append(" AND target_id = ?");
            args.add(filter.targetId());
        }
        if (filter.actorUserId() != null) {
            where.append(" AND actor_user_id = ?");
            args.add(filter.actorUserId());
        }
        if (filter.from() != null) {
            where.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND occurred_at < ?");
            args.add(Timestamp.from(filter.to()));
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_event" + where, Long.class, args.toArray());
        String direction = filter.ascending() ? "ASC" : "DESC";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<AuditEventResponse> items = jdbc.query(
                "SELECT id, actor_user_id, actor_email, action, target_type, target_id, correlation_id, "
                        + "details::text AS details, occurred_at FROM audit_event" + where
                        + " ORDER BY occurred_at " + direction + ", id " + direction + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> map(rs),
                pageArgs.toArray());
        long totalItems = total == null ? 0 : total;
        int totalPages = (int) ((totalItems + size - 1) / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }

    private AuditEventResponse map(ResultSet rs) throws SQLException {
        return new AuditEventResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_email"),
                AuditAction.valueOf(rs.getString("action")),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("correlation_id"),
                parse(rs.getString("details")),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit details are not valid JSON", e);
        }
    }
}
