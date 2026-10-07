# LedgerLab Implementation Plan

## Assumptions

1. The repository started empty (only `prompt.md`). The workspace root is the monorepo root.
2. Spring Boot **3.5.x** (latest 3.x line) is used because the brief requires 3.x, even though 4.x exists.
3. PostgreSQL 16 runs in Docker for local development (`docker compose`) and via Testcontainers for tests.
   No services are installed on the host.
4. Maven is provided through the Maven Wrapper (`./mvnw`), so no global Maven install is required.
5. Demo users exist only in the dev/e2e seed (`db/seed`), never in the base migrations.
6. One currency (USD). One processor. One external bank. All simulated.
7. No refresh tokens; access tokens last 15 minutes.

## Plan review (complexity cuts made before implementation)

The first draft was reviewed for unnecessary complexity. Changes:

- **Dropped** a separate `Capture` entity: captures are fully described by `PaymentEvent` rows
  (`CAPTURED`, amount, ledger transaction). `Refund` stays because refunds carry their own reason and
  are listed independently.
- **Dropped** an "in progress" idempotency state with expiry/sweeper: the claim lives inside the
  business transaction, so there is nothing to sweep.
- **Dropped** dispute evidence uploads and multi-step dispute workflow (`UNDER_REVIEW`, etc.):
  `OPEN → WON | LOST` is enough to demonstrate accounting and state.
- **Dropped** jqwik-spring integration: database-level randomized testing uses a seeded `Random`
  in a normal Spring test; jqwik is used for pure domain properties where it shines.
- **Kept** final capture, because without it partially captured authorizations strand customer funds.
- **Kept** trigger-maintained balances, because they let PostgreSQL itself reject overdrafts.

## Milestones and checklist

### Phase 0 – Architecture and plan
- [x] Inspect repository
- [x] `docs/architecture.md`, `docs/domain-model.md`, `docs/api-design.md`, this plan, `docs/progress.md`
- [x] Ledger convention, state machine, security model, testing strategy, risks

### Phase 1 – Foundation
- [ ] Maven wrapper, Spring Boot app, profiles (`dev`, `test`, `e2e`, `prod`)
- [ ] Docker Compose PostgreSQL, `.env.example`
- [ ] Flyway baseline migration, Actuator health/readiness
- [ ] Correlation ID filter, problem-detail error handling, JSON logging profile
- [ ] Vite + React + TS + Tailwind shell, ESLint, Prettier, Vitest
- [ ] GitHub Actions workflow
- [ ] Spotless (backend formatting) wired into CI

### Phase 2 – Identity, organizations, accounts
- [ ] Users, organizations, memberships, roles; BCrypt; JWT login; `/auth/me`
- [ ] Method security, tenant-scoped repositories
- [ ] Financial accounts + ledger account creation
- [ ] Dev/e2e seed data (two organizations, three roles)
- [ ] Integration tests: login, wrong password, role checks, cross-tenant 404

### Phase 3 – Ledger core
- [ ] Ledger tables, CHECKs, immutability triggers, deferred balance trigger, balance trigger
- [ ] `LedgerPostingService` with ordered `FOR UPDATE` locking and overdraft check
- [ ] Integrity query/endpoint
- [ ] Tests: DB rejects update/delete/unbalanced/overdraft; posting service; jqwik plan properties

### Phase 4 – Money movement and payments
- [ ] Idempotency service (sequential + concurrent tests)
- [ ] Deposits, transfers
- [ ] Payment aggregate + `PaymentStateMachine` (exhaustive tests)
- [ ] Authorize / capture / void / refund with events
- [ ] Concurrency tests (captures, refunds), rollback test, randomized model test

### Phase 5 – Disputes and audit
- [ ] Dispute open/resolve with accounting
- [ ] Audit events across all actions, auth success/failure, admin changes
- [ ] Audit API with filters; negative authorization tests

### Phase 6 – Reconciliation
- [ ] CSV parser/validator, batch import, classification, persistence, summary, results, CSV export
- [ ] Sample files (matched, mismatched, malformed)
- [ ] Tests for each classification, malformed files, duplicate import

### Phase 7 – Frontend and hardening
- [ ] All ten pages with loading/empty/error states and role gating
- [ ] Playwright flows against real backend
- [ ] Index review with `EXPLAIN` on hot queries (script)
- [ ] Remove dead code

### Phase 8 – Delivery
- [ ] README with architecture diagram, setup, credentials, decisions, limitations, screenshots
- [ ] `docs/demo-script.md`, `docs/resume-notes.md`
- [ ] `scripts/` for running everything, demo data, measurements

## Definition of done per phase

A phase is done when its tests pass locally with `./mvnw verify` (backend) and
`npm run lint && npm test && npm run build` (frontend), and `docs/progress.md` is updated.
