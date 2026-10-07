package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.account.api.AccountResponse;
import com.ledgerlab.support.IntegrationTest;
import com.ledgerlab.support.TestFixtures.Tenant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the database itself enforces ledger invariants, independent of application code. Each test
 * writes raw SQL that bypasses the Java posting service and asserts PostgreSQL rejects it.
 */
class LedgerDatabaseInvariantsIT extends IntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerService ledgerService;

    Tenant tenant;
    UUID aliceAvailable;
    UUID bobAvailable;
    UUID clearing;

    @BeforeEach
    void setUp() {
        tenant = fixtures.newTenant();
        AccountResponse alice = fixtures.fundedCustomer(tenant, "Alice", 10_000);
        AccountResponse bob = fixtures.customer(tenant, "Bob");
        aliceAvailable = ledgerAccount(alice.id(), "CUSTOMER_AVAILABLE");
        bobAvailable = ledgerAccount(bob.id(), "CUSTOMER_AVAILABLE");
        clearing = jdbc.queryForObject(
                "SELECT id FROM ledger_account WHERE organization_id = ? AND purpose = 'EXTERNAL_CLEARING'",
                UUID.class,
                tenant.organizationId());
    }

    @Test
    void cleanDatabaseIsBuiltEntirelyByFlywayMigrations() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);
        Integer failed = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(5);
        assertThat(failed).isZero();
    }

    @Test
    void ledgerEntriesCannotBeUpdated() {
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE ledger_entry SET amount_minor = amount_minor * 2 WHERE organization_id = ?",
                        tenant.organizationId()))
                .hasMessageContaining("append-only");
    }

    @Test
    void ledgerEntriesCannotBeDeleted() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entry WHERE organization_id = ?", tenant.organizationId()))
                .hasMessageContaining("append-only");
    }

    @Test
    void ledgerTransactionsCannotBeUpdatedDeletedOrTruncated() {
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE ledger_transaction SET description = 'tampered' WHERE organization_id = ?",
                        tenant.organizationId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM ledger_transaction WHERE organization_id = ?", tenant.organizationId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entry, ledger_transaction CASCADE"))
                .hasMessageContaining("append-only");
    }

    @Test
    void cachedBalanceCannotBeEditedDirectly() {
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_account SET balance_minor = -999999 WHERE id = ?", aliceAvailable))
                .hasMessageContaining("can only change by posting");
    }

    @Test
    void unbalancedJournalIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID txId = insertJournal("TRANSFER");
                    insertEntry(txId, aliceAvailable, 500);
                    insertEntry(txId, bobAvailable, -400);
                }))
                .hasStackTraceContaining("unbalanced");
        assertNoJournalWritten();
    }

    @Test
    void singleEntryJournalIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID txId = insertJournal("TRANSFER");
                    insertEntry(txId, aliceAvailable, 500);
                }))
                .hasStackTraceContaining("at least 2");
        assertNoJournalWritten();
    }

    @Test
    void journalWithoutEntriesIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> insertJournal("TRANSFER")))
                .hasStackTraceContaining("at least 2");
        assertNoJournalWritten();
    }

    @Test
    void overdraftOfCustomerAccountIsRejectedByCheckConstraint() {
        // Bob has no funds; debiting his available (liability) account would take it below zero.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID txId = insertJournal("TRANSFER");
                    insertEntry(txId, bobAvailable, 100);
                    insertEntry(txId, aliceAvailable, -100);
                }))
                .hasStackTraceContaining("ledger_account_no_overdraft");
    }

    @Test
    void onlySimulatedDepositsMayCreateMoneyThroughClearing() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID txId = insertJournal("TRANSFER");
                    insertEntry(txId, clearing, 100);
                    insertEntry(txId, bobAvailable, -100);
                }))
                .hasStackTraceContaining("only SIMULATED_DEPOSIT");
    }

    @Test
    void entryCannotReferenceAnotherOrganizationsAccount() {
        Tenant other = fixtures.newTenant();
        AccountResponse stranger = fixtures.customer(other, "Stranger");
        UUID strangerAvailable = ledgerAccount(stranger.id(), "CUSTOMER_AVAILABLE");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    UUID txId = insertJournal("TRANSFER");
                    insertEntry(txId, aliceAvailable, 100);
                    insertEntry(txId, strangerAvailable, -100);
                }))
                .hasStackTraceContaining("ledger_entry_account_fk");
    }

    @Test
    void validRawJournalIsAcceptedAndUpdatesCachedBalances() {
        tx.executeWithoutResult(status -> {
            UUID txId = insertJournal("TRANSFER");
            insertEntry(txId, aliceAvailable, 2_500);
            insertEntry(txId, bobAvailable, -2_500);
        });
        assertThat(ledgerService.presentedBalance(tenant.organizationId(), aliceAvailable)).isEqualTo(7_500);
        assertThat(ledgerService.presentedBalance(tenant.organizationId(), bobAvailable)).isEqualTo(2_500);
        assertThat(ledgerService.integrity(tenant.organizationId()).healthy()).isTrue();
    }

    private UUID insertJournal(String type) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO ledger_transaction (id, organization_id, transaction_type, currency, description,
                                                source_type, source_id, posted_at)
                VALUES (?, ?, ?, 'USD', 'raw test journal', 'TEST', ?, ?)
                """,
                id,
                tenant.organizationId(),
                type,
                UUID.randomUUID(),
                Timestamp.from(Instant.now()));
        return id;
    }

    private void insertEntry(UUID transactionId, UUID accountId, long amount) {
        jdbc.update(
                """
                INSERT INTO ledger_entry (id, ledger_transaction_id, organization_id, ledger_account_id,
                                          amount_minor, currency, created_at)
                VALUES (?, ?, ?, ?, ?, 'USD', ?)
                """,
                UUID.randomUUID(),
                transactionId,
                tenant.organizationId(),
                accountId,
                amount,
                Timestamp.from(Instant.now()));
    }

    private void assertNoJournalWritten() {
        Integer raw = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transaction WHERE organization_id = ? AND source_type = 'TEST'",
                Integer.class,
                tenant.organizationId());
        assertThat(raw).isZero();
    }

    private UUID ledgerAccount(UUID financialAccountId, String purpose) {
        return jdbc.queryForObject(
                "SELECT id FROM ledger_account WHERE financial_account_id = ? AND purpose = ?",
                UUID.class,
                financialAccountId,
                purpose);
    }
}
