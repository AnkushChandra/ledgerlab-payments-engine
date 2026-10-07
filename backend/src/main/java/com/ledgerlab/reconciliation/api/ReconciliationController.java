package com.ledgerlab.reconciliation.api;

import com.ledgerlab.reconciliation.Classification;
import com.ledgerlab.reconciliation.ReconciliationService;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import com.ledgerlab.shared.security.CurrentActor;
import com.ledgerlab.shared.security.Roles;
import com.ledgerlab.shared.web.PageRequests;
import com.ledgerlab.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/settlement-batches")
@Tag(name = "Settlement reconciliation")
public class ReconciliationController {

    private static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of(
            "text/csv", "application/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream");

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.OPERATIONS)
    @Operation(
            summary = "Upload a processor settlement CSV and reconcile it",
            description = "Header: processor_record_id,payment_id,status,amount_minor,currency,settled_at. "
                    + "The whole file is rejected if any row is invalid. Identical file content cannot be imported "
                    + "twice.")
    public ResponseEntity<SettlementBatchResponse> upload(
            CurrentActor actor,
            @Parameter(description = "Unique key per upload; retries must reuse it")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestPart("file") MultipartFile file,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd)
            throws IOException {
        String contentType = file.getContentType() == null ? "" : file.getContentType().split(";")[0].trim();
        if (!ACCEPTED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(
                    ErrorCode.INVALID_SETTLEMENT_FILE,
                    "The settlement file was rejected; nothing was imported.",
                    List.of(new ApiException.FieldError("file", "must be a CSV file")),
                    Map.of());
        }
        return reconciliationService
                .importBatch(
                        actor,
                        file.getOriginalFilename(),
                        file.getBytes(),
                        periodStart,
                        periodEnd,
                        idempotencyKey)
                .toResponseEntity();
    }

    @GetMapping
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "List settlement batches, newest first")
    public PageResponse<SettlementBatchResponse> list(
            CurrentActor actor,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return reconciliationService.list(
                actor.organizationId(),
                PageRequests.of(page, size, null, Map.of("uploadedAt", "uploadedAt"), "uploadedAt,desc"));
    }

    @GetMapping("/{batchId}")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Batch summary with counts per classification")
    public SettlementBatchResponse get(CurrentActor actor, @PathVariable UUID batchId) {
        return reconciliationService.get(actor.organizationId(), batchId);
    }

    @GetMapping("/{batchId}/results")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Reconciliation results, optionally filtered by classification")
    public PageResponse<ReconciliationResultResponse> results(
            CurrentActor actor,
            @PathVariable UUID batchId,
            @RequestParam(required = false) List<Classification> classification,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var pageable = PageRequests.of(page, size, null, Map.of("sequence", "sequence"), "sequence,asc");
        return reconciliationService.results(
                actor.organizationId(), batchId, classification, pageable.getPageNumber(), pageable.getPageSize());
    }

    @GetMapping(value = "/{batchId}/results.csv", produces = "text/csv")
    @PreAuthorize(Roles.VIEWER)
    @Operation(summary = "Download all reconciliation results as CSV")
    public ResponseEntity<byte[]> export(CurrentActor actor, @PathVariable UUID batchId) {
        String csv = reconciliationService.exportCsv(actor.organizationId(), batchId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("reconciliation-" + batchId + ".csv")
                                .build()
                                .toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
