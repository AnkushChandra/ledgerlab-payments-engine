# Resume notes (verified only)

Use these as source material for resume bullets. Do not claim coverage percentages, latency numbers or throughput unless a committed script produced them on a named machine.

## Stack (implemented and tested)

- Java 21, Spring Boot 3.5, Spring Web, Spring Data JPA, Spring Security (OAuth2 resource server, HS256 JWT), Bean Validation, Flyway, PostgreSQL 16, springdoc-openapi, Actuator, Micrometer
- React 19, TypeScript 5.9, Vite 8, React Router 7, TanStack Query 5, Tailwind CSS 4
- JUnit 5, AssertJ, jqwik, Testcontainers PostgreSQL, ArchUnit, Vitest, Playwright
- GitHub Actions, Docker Compose, Maven Wrapper

## Engineering decisions (with evidence in the repo)

- Double-entry ledger with a documented debit-positive sign convention. Every posted journal is balanced in application code (`PostingRequest`) and again by a deferred PostgreSQL constraint trigger (`V2__accounts_and_ledger.sql`).
- Ledger rows are immutable: `BEFORE UPDATE OR DELETE` and `BEFORE TRUNCATE` triggers reject mutation. Integration tests write raw SQL that bypasses the Java posting service and assert the database still rejects unbalanced journals, overdrafts, money creation outside simulated deposits, and cross-tenant entries (`LedgerDatabaseInvariantsIT`).
- Cached `ledger_account.balance_minor` is maintained only by an insert trigger. Direct updates are rejected. `GET /api/v1/ledger/integrity` recomputes balances from entries and checks the accounting equation.
- Payment mutations take `SELECT … FOR UPDATE` on the payment row, then lock ledger accounts in ascending UUID order. Concurrent capture, refund, authorization and transfer tests run on real PostgreSQL (`PaymentConcurrencyIT`).
- Mutation APIs require `Idempotency-Key`. Same key + same fingerprint replays the original response; same key + different payload returns 409. Concurrent duplicates are serialized by a unique index (`IdempotencyIT`).
- Multi-tenant isolation is enforced in repositories from the JWT `org` claim, never from the request body. Cross-tenant reads return 404 (`TenantIsolationIT`).
- Roles `ADMIN` ⊃ `OPERATIONS` ⊃ `VIEWER`. A viewer cannot perform any financial mutation (`AuthorizationIT` and a Playwright flow).
- Settlement CSV import is all-or-nothing, idempotent on content hash, and classifies matched / missing / amount / status / duplicate records (`ReconciliationIT`, `samples/`).

## Tests observed locally

2026-10-08, this machine, `backend/./mvnw -B verify` and `frontend` lint/test/build plus Playwright:

- Surefire: 127 tests, 0 failures.
- Failsafe: 76 tests, 0 failures (Testcontainers PostgreSQL 16).
- Jacoco `check`: all configured package thresholds met (no percentage claimed here beyond that gate).
- Vitest: 5 tests.
- Playwright: 5 tests (login/dashboard, authorize-capture-refund, open/resolve dispute, mismatched settlement upload, viewer restrictions).

## Suggested resume bullets

Copy only if the measurement date still matches a run you can reproduce.

- Built a double-entry payments ledger (Java 21, Spring Boot 3.5, PostgreSQL 16) where capture takes `SELECT … FOR UPDATE` on the payment row. A scripted probe of 32 concurrent full captures of one $10.00 authorization committed exactly once (31 rejected with 409); captured amount stayed $10.00.
- Kept journals balanced in application code and again with a deferred PostgreSQL constraint trigger. The same probe's integrity read reported 0 unbalanced journals and 0 accounts whose cached balance disagreed with the sum of entries.
- 203 backend tests (127 unit, 76 Testcontainers integration tests) covering idempotent replay vs conflict, concurrent capture and refund, rollback, tenant isolation, and settlement classifications, plus 5 Playwright flows against the real API.

## Measurements

`scripts/measure-capture-contention.py` against `http://localhost:8080` on 2026-10-09 (macOS arm64, API already running on the dev/e2e Postgres):

```json
{
  "attempts": 32,
  "authorizedMinor": 1000,
  "successes": 1,
  "rejected": 31,
  "outcomes": ["201:ok", "409:INVALID_PAYMENT_STATE"],
  "capturedMinor": 1000,
  "overCaptureMinor": 0,
  "unbalancedJournals": 0,
  "accountsWithBalanceDrift": 0,
  "accountingEquationHolds": true
}
```

`scripts/explain-hot-queries.sh` against the Docker Compose database after the e2e seed and Playwright run (2026-10-08). These are planner outputs, not latency numbers.

- Payment list by org + `created_at`: Index Only Scan on `payment_org_created_idx`.
- Audit events by org + `occurred_at`: Index Only Scan on `audit_event_org_occurred_idx`.
- Captured payments in a settlement window: Index Scan on `payment_org_first_captured_idx`.
- Ledger entries for one financial account: Hash Join + sequential scans. The seeded `ledger_entry` table is tiny (~52 rows); `ledger_entry_account_idx` exists in `V2__accounts_and_ledger.sql` and is expected to be chosen as the table grows. Do not cite this plan as a performance result.
