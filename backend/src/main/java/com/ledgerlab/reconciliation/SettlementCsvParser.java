package com.ledgerlab.reconciliation;

import com.ledgerlab.payment.PaymentStatus;
import com.ledgerlab.shared.Money;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Parses and validates a processor settlement file. Validation is all-or-nothing: if any row is
 * invalid the whole file is rejected with row-level errors, so a batch never contains partial data.
 */
public final class SettlementCsvParser {

    public static final List<String> HEADER =
            List.of("processor_record_id", "payment_id", "status", "amount_minor", "currency", "settled_at");
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_ROWS = 10_000;
    private static final int MAX_REPORTED_ERRORS = 50;
    private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> STATUSES =
            Arrays.stream(PaymentStatus.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());

    private SettlementCsvParser() {}

    public static List<SettlementRow> parse(String fileName, byte[] content) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw invalid("file", "must be a .csv file");
        }
        if (content.length == 0) {
            throw invalid("file", "is empty");
        }
        if (content.length > MAX_BYTES) {
            throw invalid("file", "must not exceed " + MAX_BYTES + " bytes");
        }
        String text = decodeUtf8(content);

        List<CSVRecord> records;
        try (CSVParser parser = CSVFormat.RFC4180
                .builder()
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get()
                .parse(new StringReader(text))) {
            records = parser.getRecords();
        } catch (IOException | IllegalStateException | java.io.UncheckedIOException e) {
            throw invalid("file", "is not well-formed CSV");
        }
        if (records.isEmpty()) {
            throw invalid("file", "is empty");
        }
        List<String> header = records.getFirst().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .toList();
        if (!header.equals(HEADER)) {
            throw invalid("header", "must be exactly: " + String.join(",", HEADER));
        }
        List<CSVRecord> dataRows = records.subList(1, records.size());
        if (dataRows.isEmpty()) {
            throw invalid("file", "contains no settlement records");
        }
        if (dataRows.size() > MAX_ROWS) {
            throw invalid("file", "must not contain more than " + MAX_ROWS + " records");
        }

        List<ApiException.FieldError> errors = new ArrayList<>();
        List<SettlementRow> rows = new ArrayList<>(dataRows.size());
        Map<String, Integer> seenRecordIds = new HashMap<>();
        for (CSVRecord record : dataRows) {
            int line = (int) record.getRecordNumber();
            String field = "row " + line;
            if (record.size() != HEADER.size()) {
                errors.add(new ApiException.FieldError(field, "expected " + HEADER.size() + " columns, found "
                        + record.size()));
                continue;
            }
            List<String> problems = new ArrayList<>();
            String recordId = record.get(0);
            if (!RECORD_ID.matcher(recordId).matches()) {
                problems.add("processor_record_id must be 1-64 letters, digits, '.', '_' or '-'");
            }
            UUID paymentId = parseUuid(record.get(1));
            if (paymentId == null) {
                problems.add("payment_id must be a UUID");
            }
            String status = record.get(2).toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(status)) {
                problems.add("status must be one of " + STATUSES.stream().sorted().toList());
            }
            Long amount = parseAmount(record.get(3));
            if (amount == null) {
                problems.add("amount_minor must be an integer between 0 and " + Money.MAX_OPERATION_AMOUNT);
            }
            if (!Money.CURRENCY.equals(record.get(4))) {
                problems.add("currency must be USD");
            }
            Instant settledAt = parseInstant(record.get(5));
            if (settledAt == null) {
                problems.add("settled_at must be an ISO-8601 timestamp with offset, e.g. 2026-09-02T10:00:00Z");
            }
            Integer firstSeen = seenRecordIds.putIfAbsent(recordId, line);
            if (firstSeen != null) {
                problems.add("processor_record_id duplicates row " + firstSeen);
            }
            if (problems.isEmpty()) {
                rows.add(new SettlementRow(line, recordId, paymentId, status, amount, Money.CURRENCY, settledAt));
            } else {
                problems.forEach(p -> errors.add(new ApiException.FieldError(field, p)));
            }
            if (errors.size() >= MAX_REPORTED_ERRORS) {
                break;
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(
                    ErrorCode.INVALID_SETTLEMENT_FILE,
                    "The settlement file has " + errors.size() + (errors.size() >= MAX_REPORTED_ERRORS ? "+" : "")
                            + " validation error(s); nothing was imported.",
                    errors.subList(0, Math.min(errors.size(), MAX_REPORTED_ERRORS)),
                    Map.of());
        }
        return rows;
    }

    private static String decodeUtf8(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            if (text.indexOf('\0') >= 0) {
                throw invalid("file", "must be a UTF-8 text file");
            }
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw invalid("file", "must be a UTF-8 text file");
        }
    }

    private static UUID parseUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long parseAmount(String value) {
        if (!value.matches("\\d{1,12}")) {
            return null;
        }
        long amount = Long.parseLong(value);
        return amount <= Money.MAX_OPERATION_AMOUNT ? amount : null;
    }

    private static Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(
                ErrorCode.INVALID_SETTLEMENT_FILE,
                "The settlement file was rejected; nothing was imported.",
                List.of(new ApiException.FieldError(field, message)),
                Map.of());
    }
}
