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

Run `./scripts/test.sh` and record the Surefire/Failsafe suite counts from that run. Do not invent numbers here.

Playwright covers: login and dashboard, authorize/capture/refund, open/resolve dispute, mismatched settlement upload, viewer restrictions.

## Measurements

None recorded yet. After a local run of `./scripts/explain-hot-queries.sh` against seeded data, paste the plans here if they are used in a resume bullet.
