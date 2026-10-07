package com.ledgerlab.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ledgerlab.shared.Hashing;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.metrics.LedgerLabMetrics;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes a mutation at most once per (organization, operation, key).
 *
 * <p>The claim row is inserted inside the caller's transaction with {@code ON CONFLICT DO NOTHING}.
 * A concurrent request using the same key blocks on PostgreSQL's unique index until the first
 * transaction finishes: if it committed, the second request reads and replays the stored response;
 * if it rolled back, the second request's insert succeeds and it executes normally. There is
 * therefore no "in progress" state to expire, and failed requests never consume a key.
 */
@Service
public class IdempotencyService {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,100}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper storageMapper;
    private final ObjectMapper canonicalMapper;
    private final Clock clock;
    private final LedgerLabMetrics metrics;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock, LedgerLabMetrics metrics) {
        this.jdbc = jdbc;
        this.storageMapper = objectMapper;
        this.canonicalMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> IdempotentResult<T> execute(
            IdempotentRequest request, Class<T> responseType, Supplier<IdempotentResponse<T>> operation) {
        String key = validateKey(request.key());
        String requestHash = fingerprint(request);

        int claimed = jdbc.update(
                """
                INSERT INTO idempotency_record (id, organization_id, operation, idempotency_key, request_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, operation, idempotency_key) DO NOTHING
                """,
                UUID.randomUUID(),
                request.organizationId(),
                request.operation(),
                key,
                requestHash,
                Timestamp.from(clock.instant()));

        if (claimed == 0) {
            return replay(request, key, requestHash, responseType);
        }

        IdempotentResponse<T> response = operation.get();
        jdbc.update(
                """
                UPDATE idempotency_record
                   SET response_status = ?, response_body = ?::jsonb, resource_id = ?, completed_at = ?
                 WHERE organization_id = ? AND operation = ? AND idempotency_key = ?
                """,
                response.status(),
                toJson(response.body()),
                response.resourceId(),
                Timestamp.from(clock.instant()),
                request.organizationId(),
                request.operation(),
                key);
        metrics.idempotency("executed");
        return new IdempotentResult<>(response.status(), response.body(), false);
    }

    private <T> IdempotentResult<T> replay(
            IdempotentRequest request, String key, String requestHash, Class<T> responseType) {
        List<StoredRecord> rows = jdbc.query(
                """
                SELECT request_hash, response_status, response_body::text AS body
                  FROM idempotency_record
                 WHERE organization_id = ? AND operation = ? AND idempotency_key = ?
                """,
                (rs, rowNum) -> new StoredRecord(
                        rs.getString("request_hash"), (Integer) rs.getObject("response_status"), rs.getString("body")),
                request.organizationId(),
                request.operation(),
                key);
        StoredRecord stored = rows.getFirst();
        if (!stored.requestHash().equals(requestHash)) {
            metrics.idempotency("conflict");
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request. Use a new key for a new operation.");
        }
        if (stored.status() == null) {
            // Only reachable if a record was claimed in this same transaction and not yet completed.
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The original request is still in progress.");
        }
        metrics.idempotency("replayed");
        return new IdempotentResult<>(stored.status(), fromJson(stored.body(), responseType), true);
    }

    String fingerprint(IdempotentRequest request) {
        try {
            String canonical = request.operation() + "\n" + request.scope() + "\n"
                    + canonicalMapper.writeValueAsString(request.payload());
            return Hashing.sha256Hex(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Request payload cannot be fingerprinted", e);
        }
    }

    private static String validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "This operation requires an Idempotency-Key header.");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new ApiException(
                    ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must be 8-100 characters of letters, digits, '-' or '_'.");
        }
        return key;
    }

    private String toJson(Object body) {
        try {
            return storageMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response cannot be stored", e);
        }
    }

    private <T> T fromJson(String body, Class<T> type) {
        try {
            return storageMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response cannot be read", e);
        }
    }

    private record StoredRecord(String requestHash, Integer status, String body) {}
}
