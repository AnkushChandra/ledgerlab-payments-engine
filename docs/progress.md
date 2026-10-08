# LedgerLab Progress

## Completed

- Phase 0: architecture, domain model, API design, implementation plan.
- Phase 1: Maven wrapper, Spring Boot 3.5, Vite/React frontend, Docker Compose PostgreSQL, GitHub Actions, Actuator, correlation IDs, RFC 7807 errors.
- Phase 2: JWT login, organizations, memberships, roles, financial accounts, demo seed, tenant isolation tests.
- Phase 3: immutable double-entry ledger, trigger-maintained balances, integrity endpoint, property tests, DB invariant tests.
- Phase 4: deposits, transfers, payment state machine, capture/void/refund, idempotency, concurrent-operation tests, rollback tests.
- Phase 5: disputes (hold / win / chargeback), append-only audit trail, authorization tests.
- Phase 6: settlement CSV import, classification engine, result export, sample files.
- Frontend pages: login, overview, accounts, payments (with authorize/capture/void/refund), deposits and transfers, disputes, reconciliation, audit.
- Playwright critical flows and CI workflow defined.

## Active

- Local verification of frontend lint/unit tests, full backend `verify` (including Jacoco), and Playwright against Docker Postgres.

## Remaining

- Capture README screenshots from a running local instance.
- Record EXPLAIN output from `scripts/explain-hot-queries.sh` into resume notes after a local run.
- Confirm Jacoco package thresholds pass on a clean `./mvnw verify`.

## Test results

Backend integration tests last observed passing (Testcontainers PostgreSQL): ledger invariants, payment lifecycle, concurrency, rollback, idempotency, disputes, authentication, authorization, tenant isolation, reconciliation, seed data.

Frontend unit tests and Playwright have not been recorded in this file from a completed local run yet.

## Known limitations

- USD only. No refresh tokens (15-minute access tokens).
- External bank and card processor are local simulators.
- Dispute workflow is OPEN → WON | LOST (no evidence uploads).
- JWT secret is a well-known value in the `dev`/`e2e` profiles only; production must set `LEDGERLAB_JWT_SECRET`.
- Demo password `LedgerLab!2026` exists only in the seed loaded by those profiles.
