package com.ledgerlab.reconciliation;

import com.ledgerlab.audit.AuditAction;
import com.ledgerlab.audit.AuditService;
import com.ledgerlab.payment.Payment;
import com.ledgerlab.payment.PaymentRepository;
import com.ledgerlab.reconciliation.api.ReconciliationResultResponse;
import com.ledgerlab.reconciliation.api.SettlementBatchResponse;
import com.ledgerlab.shared.Hashing;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.idempotency.IdempotencyService;
import com.ledgerlab.shared.idempotency.IdempotentRequest;
import com.ledgerlab.shared.idempotency.IdempotentResponse;
import com.ledgerlab.shared.idempotency.IdempotentResult;
import com.ledgerlab.shared.metrics.LedgerLabMetrics;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.web.PageResponse;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    public static final int MAX_PERIOD_DAYS = 31;

    private final SettlementBatchRepository batches;
    private final PaymentRepository payments;
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final LedgerLabMetrics metrics;
    private final Clock clock;

    public ReconciliationService(
            SettlementBatchRepository batches,
            PaymentRepository payments,
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            AuditService audit,
            LedgerLabMetrics metrics,
            Clock clock) {
        this.batches = batches;
        this.payments = payments;
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    record ImportFingerprint(String contentSha256, LocalDate periodStart, LocalDate periodEnd) {}

    @Transactional
    public IdempotentResult<SettlementBatchResponse> importBatch(
            CurrentActor actor,
            String fileName,
            byte[] content,
            LocalDate periodStart,
            LocalDate periodEnd,
            String idempotencyKey) {
        validatePeriod(periodStart, periodEnd);
        String sha256 = Hashing.sha256Hex(content);
        var idempotent = IdempotentRequest.of(
                actor.organizationId(),
                "SETTLEMENT_IMPORT",
                idempotencyKey,
                new ImportFingerprint(sha256, periodStart, periodEnd));
        return idempotency.execute(idempotent, SettlementBatchResponse.class, () -> {
            batches.findByOrganizationIdAndContentSha256(actor.organizationId(), sha256)
                    .ifPresent(existing -> {
                        throw new ApiException(
                                ErrorCode.DUPLICATE_SETTLEMENT_FILE,
                                "This settlement file was already imported.",
                                List.of(),
                                Map.of("existingBatchId", existing.getId()));
                    });
            List<SettlementRow> rows = SettlementCsvParser.parse(fileName, content);

            List<UUID> referencedIds =
                    rows.stream().map(SettlementRow::paymentId).distinct().toList();
            Map<UUID, ReconciliationEngine.InternalPayment> internalById = payments
                    .findByOrganizationIdAndIdIn(actor.organizationId(), referencedIds)
                    .stream()
                    .map(ReconciliationService::toInternal)
                    .collect(Collectors.toMap(ReconciliationEngine.InternalPayment::id, Function.identity()));
            Instant from = periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            List<ReconciliationEngine.InternalPayment> capturedInPeriod =
                    payments.findCapturedBetween(actor.organizationId(), from, to).stream()
                            .map(ReconciliationService::toInternal)
                            .toList();

            List<ReconciliationEngine.Result> results =
                    ReconciliationEngine.reconcile(rows, internalById, capturedInPeriod);

            UUID batchId = UUID.randomUUID();
            SettlementBatch batch = new SettlementBatch(
                    batchId,
                    actor.organizationId(),
                    sanitizeFileName(fileName),
                    sha256,
                    periodStart,
                    periodEnd,
                    rows.size(),
                    results,
                    actor.userId(),
                    clock.instant());
            batches.saveAndFlush(batch);
            Map<Integer, UUID> recordIds = insertRecords(batch, rows);
            insertResults(batch, results, recordIds);

            audit.record(
                    actor,
                    AuditAction.SETTLEMENT_IMPORTED,
                    "SETTLEMENT_BATCH",
                    batchId,
                    Map.of("fileName", batch.getFileName(), "records", rows.size(), "sha256", sha256));
            audit.record(
                    actor,
                    AuditAction.RECONCILIATION_COMPLETED,
                    "SETTLEMENT_BATCH",
                    batchId,
                    Map.of("counts", batch.counts(), "exceptions", batch.exceptionCount()));
            batch.counts().forEach((classification, count) ->
                    metrics.reconciliationResults(classification.name(), count));
            return IdempotentResponse.created(SettlementBatchResponse.from(batch), batchId);
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<SettlementBatchResponse> list(UUID organizationId, Pageable pageable) {
        return PageResponse.of(batches.findByOrganizationId(organizationId, pageable), SettlementBatchResponse::from);
    }

    @Transactional(readOnly = true)
    public SettlementBatchResponse get(UUID organizationId, UUID batchId) {
        return SettlementBatchResponse.from(find(organizationId, batchId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReconciliationResultResponse> results(
            UUID organizationId, UUID batchId, List<Classification> classifications, int page, int size) {
        find(organizationId, batchId);
        String filter = classifications == null || classifications.isEmpty()
                ? ""
                : " AND r.classification IN ("
                        + classifications.stream().map(c -> "'" + c.name() + "'").collect(Collectors.joining(","))
                        + ")";
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_result r WHERE r.batch_id = ? AND r.organization_id = ?" + filter,
                Long.class,
                batchId,
                organizationId);
        List<ReconciliationResultResponse> items = jdbc.query(
                RESULT_SELECT + " WHERE r.batch_id = ? AND r.organization_id = ?" + filter
                        + " ORDER BY r.sequence_number LIMIT ? OFFSET ?",
                (rs, rowNum) -> ReconciliationResultResponse.from(rs),
                batchId,
                organizationId,
                size,
                (long) page * size);
        long totalItems = total == null ? 0 : total;
        return new PageResponse<>(items, page, size, totalItems, (int) ((totalItems + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID organizationId, UUID batchId) {
        find(organizationId, batchId);
        List<ReconciliationResultResponse> all = jdbc.query(
                RESULT_SELECT + " WHERE r.batch_id = ? AND r.organization_id = ? ORDER BY r.sequence_number",
                (rs, rowNum) -> ReconciliationResultResponse.from(rs),
                batchId,
                organizationId);
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(
                out,
                CSVFormat.RFC4180
                        .builder()
                        .setHeader(
                                "sequence", "classification", "row_number", "processor_record_id", "payment_id",
                                "expected_status", "actual_status", "expected_amount_minor", "actual_amount_minor",
                                "message")
                        .get())) {
            for (ReconciliationResultResponse r : all) {
                printer.printRecord(
                        r.sequenceNumber(),
                        r.classification(),
                        r.rowNumber(),
                        r.processorRecordId(),
                        r.paymentId(),
                        r.expectedStatus(),
                        r.actualStatus(),
                        r.expectedAmountMinor(),
                        r.actualAmountMinor(),
                        r.message());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static final String RESULT_SELECT =
            """
            SELECT r.id, r.sequence_number, r.classification, r.payment_id, r.expected_amount_minor,
                   r.actual_amount_minor, r.expected_status, r.actual_status, r.message,
                   s.row_number, s.processor_record_id, s.settled_at
              FROM reconciliation_result r
              LEFT JOIN settlement_record s ON s.id = r.settlement_record_id
            """;

    private Map<Integer, UUID> insertRecords(SettlementBatch batch, List<SettlementRow> rows) {
        Map<Integer, UUID> ids = new java.util.HashMap<>();
        rows.forEach(row -> ids.put(row.rowNumber(), UUID.randomUUID()));
        jdbc.batchUpdate(
                """
                INSERT INTO settlement_record (id, batch_id, organization_id, row_number, processor_record_id,
                                               payment_id, status, amount_minor, currency, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows,
                500,
                (ps, row) -> {
                    ps.setObject(1, ids.get(row.rowNumber()));
                    ps.setObject(2, batch.getId());
                    ps.setObject(3, batch.getOrganizationId());
                    ps.setInt(4, row.rowNumber());
                    ps.setString(5, row.processorRecordId());
                    ps.setObject(6, row.paymentId());
                    ps.setString(7, row.status());
                    ps.setLong(8, row.amountMinor());
                    ps.setString(9, row.currency());
                    ps.setTimestamp(10, Timestamp.from(row.settledAt()));
                });
        return ids;
    }

    private void insertResults(
            SettlementBatch batch, List<ReconciliationEngine.Result> results, Map<Integer, UUID> recordIds) {
        List<Object[]> args = new ArrayList<>(results.size());
        int sequence = 1;
        for (ReconciliationEngine.Result r : results) {
            args.add(new Object[] {
                UUID.randomUUID(),
                batch.getId(),
                batch.getOrganizationId(),
                r.row() == null ? null : recordIds.get(r.row().rowNumber()),
                r.paymentId(),
                r.classification().name(),
                r.expectedAmountMinor(),
                r.actualAmountMinor(),
                r.expectedStatus(),
                r.actualStatus(),
                r.message(),
                sequence++
            });
        }
        jdbc.batchUpdate(
                """
                INSERT INTO reconciliation_result (id, batch_id, organization_id, settlement_record_id, payment_id,
                                                   classification, expected_amount_minor, actual_amount_minor,
                                                   expected_status, actual_status, message, sequence_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                args);
    }

    private SettlementBatch find(UUID organizationId, UUID batchId) {
        return batches.findByIdAndOrganizationId(batchId, organizationId)
                .orElseThrow(() -> ApiException.notFound("Settlement batch"));
    }

    private static ReconciliationEngine.InternalPayment toInternal(Payment p) {
        return new ReconciliationEngine.InternalPayment(
                p.getId(), p.getStatus().name(), p.netSettledMinor(), p.getFirstCapturedAt());
    }

    private static void validatePeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw periodError("periodStart and periodEnd are required");
        }
        if (end.isBefore(start)) {
            throw periodError("periodEnd must not be before periodStart");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_PERIOD_DAYS) {
            throw periodError("the period must not exceed " + MAX_PERIOD_DAYS + " days");
        }
    }

    private static ApiException periodError(String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Request validation failed.",
                List.of(new ApiException.FieldError("periodEnd", message)),
                Map.of());
    }

    private static String sanitizeFileName(String fileName) {
        String base = fileName == null ? "settlement.csv" : fileName.replaceAll("^.*[/\\\\]", "");
        base = base.replaceAll("[^A-Za-z0-9._ -]", "_");
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
