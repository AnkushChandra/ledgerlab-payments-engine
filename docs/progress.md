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
- Playwright critical flows, Docker Compose, GitHub Actions, demo script, resume notes, README screenshots.

## Active

- None. Local verification of backend `verify`, frontend lint/unit/build, and Playwright against Docker Postgres has been run.

## Remaining

- Optional: capture a dedicated settlement-exceptions screenshot at desktop width for the README.
- GitHub Actions on `main` will independently confirm CI (including the e2e job that starts Compose + backend + Playwright).

## Measurement (2026-10-09)

`python3 scripts/measure-capture-contention.py` against the local API: 32 concurrent full captures of a $10.00 authorization → 1 committed, 31 rejected (`409 INVALID_PAYMENT_STATE`), captured amount $10.00, over-capture $0.00. Integrity read on that response: 0 unbalanced journals, 0 balance-drift accounts, accounting equation held.

## Test results

Observed 2026-10-08 on this machine (`darwin`, Java 21.0.8, Node 25, PostgreSQL 16 via Docker):

- Backend `./mvnw verify`: **127** Surefire tests, **76** Failsafe integration tests, Jacoco `check` reported all coverage checks met, `BUILD SUCCESS`.
- Frontend: lint, Prettier, 5 Vitest tests, production build.
- Playwright (`npx playwright test` against `spring-boot:run` profile `e2e` + Vite preview on :4173): **5 passed** (login/dashboard, authorize-capture-refund, dispute, mismatched settlement upload, viewer gating).

## Known limitations

- USD only. No refresh tokens (15-minute access tokens).
- External bank and card processor are local simulators.
- Dispute workflow is OPEN → WON | LOST (no evidence uploads).
- JWT secret is a well-known value in the `dev`/`e2e` profiles only; production must set `LEDGERLAB_JWT_SECRET`.
- Demo password `LedgerLab!2026` exists only in the seed loaded by those profiles.
