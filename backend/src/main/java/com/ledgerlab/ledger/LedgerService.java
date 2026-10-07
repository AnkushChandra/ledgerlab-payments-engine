package com.ledgerlab.ledger;

import com.ledgerlab.shared.Money;
import com.ledgerlab.shared.error.ApiException;
import com.ledgerlab.shared.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component that writes ledger rows.
 *
 * <p>Posting protocol: lock every affected ledger account with {@code SELECT ... FOR UPDATE} in
 * ascending id order (a global order, so concurrent postings cannot deadlock), verify that no
 * non-negative account would be overdrawn, then insert the journal and its entries. A database
 * trigger applies each entry to {@code ledger_account.balance_minor}; CHECK constraints and a
 * deferred trigger re-verify the invariants at commit as a backstop.
 */
@Service
public class LedgerService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LedgerService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Creates the ledger accounts backing a financial account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<LedgerAccountPurpose, UUID> createAccounts(
            UUID organizationId, UUID financialAccountId, List<LedgerAccountPurpose> purposes) {
        Map<LedgerAccountPurpose, UUID> created = new EnumMap<>(LedgerAccountPurpose.class);
        Timestamp now = Timestamp.from(clock.instant());
        for (LedgerAccountPurpose purpose : purposes) {
            if (purpose == LedgerAccountPurpose.EXTERNAL_CLEARING) {
                throw new IllegalArgumentException("Clearing accounts belong to the organization");
            }
            UUID id = UUID.randomUUID();
            insertAccount(id, organizationId, financialAccountId, purpose, now);
            created.put(purpose, id);
        }
        return created;
    }

    /** Returns the organization's clearing account, creating it on first use. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID clearingAccountId(UUID organizationId) {
        jdbc.update(
                """
                INSERT INTO ledger_account (id, organization_id, financial_account_id, purpose, account_type,
                                            normal_side, currency, allow_negative, created_at)
                VALUES (?, ?, NULL, 'EXTERNAL_CLEARING', 'ASSET', 'DEBIT', 'USD', true, ?)
                ON CONFLICT (organization_id) WHERE purpose = 'EXTERNAL_CLEARING' DO NOTHING
                """,
                UUID.randomUUID(),
                organizationId,
                Timestamp.from(clock.instant()));
        return jdbc.queryForObject(
                "SELECT id FROM ledger_account WHERE organization_id = ? AND purpose = 'EXTERNAL_CLEARING'",
                UUID.class,
                organizationId);
    }

    /** Ledger account ids of a financial account, keyed by purpose. */
    @Transactional(readOnly = true)
    public Map<LedgerAccountPurpose, UUID> accountsOf(UUID organizationId, UUID financialAccountId) {
        Map<LedgerAccountPurpose, UUID> result = new EnumMap<>(LedgerAccountPurpose.class);
        jdbc.query(
                "SELECT id, purpose FROM ledger_account WHERE organization_id = ? AND financial_account_id = ?",
                rs -> {
                    result.put(
                            LedgerAccountPurpose.valueOf(rs.getString("purpose")), rs.getObject("id", UUID.class));
                },
                organizationId,
                financialAccountId);
        return result;
    }

    /**
     * Locks the given accounts in ascending id order and returns their current state. Callers that
     * need to inspect balances before posting must lock the full set of accounts the posting will
     * touch, so that every transaction acquires locks in the same global order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, LockedAccount> lockAccounts(UUID organizationId, List<UUID> ledgerAccountIds) {
        UUID[] ids = ledgerAccountIds.stream().distinct().sorted().toArray(UUID[]::new);
        Map<UUID, LockedAccount> locked = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT id, purpose, balance_minor
                  FROM ledger_account
                 WHERE organization_id = ? AND id = ANY (?)
                 ORDER BY id
                   FOR UPDATE
                """,
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    locked.put(
                            id,
                            new LockedAccount(
                                    id,
                                    LedgerAccountPurpose.valueOf(rs.getString("purpose")),
                                    rs.getLong("balance_minor")));
                },
                organizationId,
                ids);
        if (locked.size() != ids.length) {
            throw new IllegalStateException("Ledger account not found in organization " + organizationId);
        }
        return locked;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(PostingRequest request) {
        List<UUID> accountIds =
                request.lines().stream().map(PostingLine::ledgerAccountId).toList();
        Map<UUID, LockedAccount> accounts = lockAccounts(request.organizationId(), accountIds);

        Map<UUID, Long> deltas = new HashMap<>();
        for (PostingLine line : request.lines()) {
            deltas.merge(line.ledgerAccountId(), line.amountMinor(), Math::addExact);
        }
        for (var delta : deltas.entrySet()) {
            LockedAccount account = accounts.get(delta.getKey());
            if (account.purpose() == LedgerAccountPurpose.EXTERNAL_CLEARING
                    && request.type() != LedgerTransactionType.SIMULATED_DEPOSIT) {
                throw new IllegalStateException("Only simulated deposits may post to the clearing account");
            }
            long after = Math.addExact(account.balanceMinor(), delta.getValue());
            if (!account.purpose().allowNegative() && account.purpose().presented(after) < 0) {
                throw insufficientFunds(account, -account.purpose().presented(delta.getValue()));
            }
        }

        UUID transactionId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                """
                INSERT INTO ledger_transaction (id, organization_id, transaction_type, currency, description,
                                                source_type, source_id, posted_at, created_by)
                VALUES (?, ?, ?, 'USD', ?, ?, ?, ?, ?)
                """,
                transactionId,
                request.organizationId(),
                request.type().name(),
                request.description(),
                request.sourceType(),
                request.sourceId(),
                now,
                request.createdBy());
        jdbc.batchUpdate(
                """
                INSERT INTO ledger_entry (id, ledger_transaction_id, organization_id, ledger_account_id,
                                          amount_minor, currency, created_at)
                VALUES (?, ?, ?, ?, ?, 'USD', ?)
                """,
                request.lines(),
                request.lines().size(),
                (ps, line) -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, transactionId);
                    ps.setObject(3, request.organizationId());
                    ps.setObject(4, line.ledgerAccountId());
                    ps.setLong(5, line.amountMinor());
                    ps.setTimestamp(6, now);
                });
        return transactionId;
    }

    /** Presented balance of a single account (positive = holds funds on its normal side). */
    @Transactional(readOnly = true)
    public long presentedBalance(UUID organizationId, UUID ledgerAccountId) {
        return jdbc.queryForObject(
                "SELECT purpose, balance_minor FROM ledger_account WHERE organization_id = ? AND id = ?",
                (rs, rowNum) -> LedgerAccountPurpose.valueOf(rs.getString("purpose"))
                        .presented(rs.getLong("balance_minor")),
                organizationId,
                ledgerAccountId);
    }

    /** Presented balances of every ledger account owned by the given financial accounts. */
    @Transactional(readOnly = true)
    public Map<UUID, Map<LedgerAccountPurpose, Long>> balancesOf(UUID organizationId, List<UUID> financialAccountIds) {
        Map<UUID, Map<LedgerAccountPurpose, Long>> result = new HashMap<>();
        if (financialAccountIds.isEmpty()) {
            return result;
        }
        jdbc.query(
                """
                SELECT financial_account_id, purpose, balance_minor
                  FROM ledger_account
                 WHERE organization_id = ? AND financial_account_id = ANY (?)
                """,
                rs -> {
                    LedgerAccountPurpose purpose = LedgerAccountPurpose.valueOf(rs.getString("purpose"));
                    result.computeIfAbsent(
                                    rs.getObject("financial_account_id", UUID.class),
                                    k -> new EnumMap<>(LedgerAccountPurpose.class))
                            .put(purpose, purpose.presented(rs.getLong("balance_minor")));
                },
                organizationId,
                financialAccountIds.toArray(UUID[]::new));
        return result;
    }

    /** Sum of presented balances per purpose across the organization (for the dashboard). */
    @Transactional(readOnly = true)
    public Map<LedgerAccountPurpose, Long> totalsByPurpose(UUID organizationId) {
        Map<LedgerAccountPurpose, Long> totals = new EnumMap<>(LedgerAccountPurpose.class);
        jdbc.query(
                "SELECT purpose, sum(balance_minor) AS total FROM ledger_account WHERE organization_id = ? GROUP BY purpose",
                rs -> {
                    LedgerAccountPurpose purpose = LedgerAccountPurpose.valueOf(rs.getString("purpose"));
                    totals.put(purpose, purpose.presented(rs.getLong("total")));
                },
                organizationId);
        return totals;
    }

    @Transactional(readOnly = true)
    public LedgerEntryPage entriesOfFinancialAccount(UUID organizationId, UUID financialAccountId, int page, int size) {
        Long total = jdbc.queryForObject(
                """
                SELECT count(*) FROM ledger_entry e
                  JOIN ledger_account a ON a.id = e.ledger_account_id
                 WHERE a.organization_id = ? AND a.financial_account_id = ?
                """,
                Long.class,
                organizationId,
                financialAccountId);
        List<LedgerEntryView> items = jdbc.query(
                """
                SELECT * FROM (
                    SELECT e.id, e.ledger_transaction_id, t.transaction_type, t.description, t.source_type,
                           t.source_id, a.id AS ledger_account_id, a.purpose, e.amount_minor, e.created_at,
                           sum(e.amount_minor) OVER (PARTITION BY a.id ORDER BY e.created_at, e.id) AS raw_balance_after
                      FROM ledger_entry e
                      JOIN ledger_account a ON a.id = e.ledger_account_id
                      JOIN ledger_transaction t ON t.id = e.ledger_transaction_id
                     WHERE a.organization_id = ? AND a.financial_account_id = ?
                ) entries
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> {
                    LedgerAccountPurpose purpose = LedgerAccountPurpose.valueOf(rs.getString("purpose"));
                    long amount = rs.getLong("amount_minor");
                    return new LedgerEntryView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("ledger_transaction_id", UUID.class),
                            LedgerTransactionType.valueOf(rs.getString("transaction_type")),
                            rs.getString("description"),
                            rs.getString("source_type"),
                            rs.getObject("source_id", UUID.class),
                            rs.getObject("ledger_account_id", UUID.class),
                            purpose,
                            amount,
                            amount > 0 ? "DEBIT" : "CREDIT",
                            purpose.presented(amount),
                            purpose.presented(rs.getLong("raw_balance_after")),
                            rs.getTimestamp("created_at").toInstant());
                },
                organizationId,
                financialAccountId,
                size,
                (long) page * size);
        return new LedgerEntryPage(items, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public LedgerTransactionView getTransaction(UUID organizationId, UUID transactionId) {
        List<LedgerTransactionView.Header> headers = jdbc.query(
                """
                SELECT id, transaction_type, currency, description, source_type, source_id, posted_at, created_by
                  FROM ledger_transaction WHERE organization_id = ? AND id = ?
                """,
                (rs, rowNum) -> new LedgerTransactionView.Header(
                        rs.getObject("id", UUID.class),
                        LedgerTransactionType.valueOf(rs.getString("transaction_type")),
                        rs.getString("currency"),
                        rs.getString("description"),
                        rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class),
                        rs.getTimestamp("posted_at").toInstant(),
                        rs.getObject("created_by", UUID.class)),
                organizationId,
                transactionId);
        if (headers.isEmpty()) {
            throw ApiException.notFound("Ledger transaction");
        }
        List<LedgerTransactionView.Entry> entries = jdbc.query(
                """
                SELECT e.id, e.ledger_account_id, a.purpose, a.financial_account_id, e.amount_minor
                  FROM ledger_entry e JOIN ledger_account a ON a.id = e.ledger_account_id
                 WHERE e.organization_id = ? AND e.ledger_transaction_id = ?
                 ORDER BY e.amount_minor DESC, e.id
                """,
                (rs, rowNum) -> {
                    long amount = rs.getLong("amount_minor");
                    return new LedgerTransactionView.Entry(
                            rs.getObject("id", UUID.class),
                            rs.getObject("ledger_account_id", UUID.class),
                            LedgerAccountPurpose.valueOf(rs.getString("purpose")),
                            rs.getObject("financial_account_id", UUID.class),
                            amount,
                            amount > 0 ? "DEBIT" : "CREDIT");
                },
                organizationId,
                transactionId);
        return new LedgerTransactionView(headers.getFirst(), entries);
    }

    /**
     * Recomputes every invariant from raw entries: journals balance, cached balances equal the sum
     * of entries, and the clearing asset equals total liabilities.
     */
    @Transactional(readOnly = true)
    public IntegrityReport integrity(UUID organizationId) {
        long transactions = count("SELECT count(*) FROM ledger_transaction WHERE organization_id = ?", organizationId);
        long entries = count("SELECT count(*) FROM ledger_entry WHERE organization_id = ?", organizationId);
        long unbalanced = count(
                """
                SELECT count(*) FROM (
                    SELECT t.id FROM ledger_transaction t
                      LEFT JOIN ledger_entry e ON e.ledger_transaction_id = t.id
                     WHERE t.organization_id = ?
                     GROUP BY t.id
                    HAVING coalesce(sum(e.amount_minor), 0) <> 0 OR count(e.id) < 2
                ) bad
                """,
                organizationId);
        long drifted = count(
                """
                SELECT count(*) FROM ledger_account a
                  LEFT JOIN (SELECT ledger_account_id, sum(amount_minor) AS total
                               FROM ledger_entry WHERE organization_id = ? GROUP BY ledger_account_id) s
                    ON s.ledger_account_id = a.id
                 WHERE a.organization_id = ? AND a.balance_minor <> coalesce(s.total, 0)
                """,
                organizationId,
                organizationId);
        Map<LedgerAccountPurpose, Long> totals = totalsByPurpose(organizationId);
        long clearing = totals.getOrDefault(LedgerAccountPurpose.EXTERNAL_CLEARING, 0L);
        long liabilities = totals.entrySet().stream()
                .filter(e -> e.getKey().accountType() == LedgerAccountPurpose.AccountType.LIABILITY)
                .mapToLong(Map.Entry::getValue)
                .sum();
        boolean equationHolds = clearing == liabilities;
        return new IntegrityReport(
                unbalanced == 0 && drifted == 0 && equationHolds,
                transactions,
                entries,
                unbalanced,
                drifted,
                clearing,
                liabilities,
                equationHolds,
                Instant.now(clock));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void insertAccount(
            UUID id, UUID organizationId, UUID financialAccountId, LedgerAccountPurpose purpose, Timestamp now) {
        jdbc.update(
                """
                INSERT INTO ledger_account (id, organization_id, financial_account_id, purpose, account_type,
                                            normal_side, currency, allow_negative, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'USD', ?, ?)
                """,
                id,
                organizationId,
                financialAccountId,
                purpose.name(),
                purpose.accountType().name(),
                purpose.normalSide().name(),
                purpose.allowNegative(),
                now);
    }

    private static ApiException insufficientFunds(LockedAccount account, long required) {
        long available = account.purpose().presented(account.balanceMinor());
        ErrorCode code =
                account.purpose().isMerchantAccount() ? ErrorCode.MERCHANT_INSUFFICIENT_FUNDS : ErrorCode.INSUFFICIENT_FUNDS;
        return new ApiException(
                code,
                "Insufficient funds: " + Money.format(available) + " available, " + Money.format(required)
                        + " required.",
                List.of(),
                Map.of("availableMinor", available, "requiredMinor", required));
    }

    public record LockedAccount(UUID id, LedgerAccountPurpose purpose, long balanceMinor) {
        public long presentedBalance() {
            return purpose.presented(balanceMinor);
        }
    }

    public record LedgerEntryPage(List<LedgerEntryView> items, long totalItems) {}
}
