# LedgerLab

A double-entry payments and settlement-reconciliation platform. Every balance change is an immutable, balanced journal. PostgreSQL enforces the financial invariants even if application code is wrong.

Built as a **modular monolith**: one Spring Boot API, one PostgreSQL database, one React dashboard. No message broker, no microservices, no cloud vendor lock-in.

## Why this exists

Portfolio-quality backend work is usually either a CRUD app or an infrastructure diagram. LedgerLab is the middle: a product a finance operator could actually use, with the hard parts in the domain.

- Capture cannot exceed authorization; refunds cannot exceed captured funds; both hold under concurrent requests.
- The only way money enters the system is an explicitly labeled simulated bank deposit.
- A viewer in organization A cannot see organization B's accounts, payments, audit events or settlement files.
- Uploading a processor settlement CSV classifies matched, missing, duplicated and mismatched records.

## Architecture

```text
Browser (React 19 + Vite)
        │  JSON + JWT
        ▼
Spring Boot 3.5 API  ── Flyway ── PostgreSQL 16
  auth │ organization │ account │ ledger
  funds │ payment │ dispute │ reconciliation │ audit
```

Accounting convention: **positive entry = debit, negative = credit**. Liability accounts (customer/merchant) present `-balance`. See [docs/domain-model.md](docs/domain-model.md).

Concurrency: `SELECT … FOR UPDATE` on the payment row, then ledger accounts locked in UUID order. Idempotent mutations use `INSERT … ON CONFLICT DO NOTHING` inside the same database transaction.

## Quick start

Requires **Java 21**, **Node 22+**, **Docker**. Maven is the wrapper in `backend/`.

```bash
docker compose up -d --wait
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# in another terminal
cd frontend && npm install && npm run dev
```

Or `./scripts/dev.sh` from the repository root.

| | |
|---|---|
| App | http://localhost:5173 |
| API | http://localhost:8080/api/v1 |
| OpenAPI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

PostgreSQL is published on **localhost:5433** so it does not collide with a local Postgres on 5432.

### Demo credentials (local development only)

| Email | Role | Password |
|-------|------|----------|
| `ops@acme.test` | OPERATIONS | `LedgerLab!2026` |
| `admin@acme.test` | ADMIN | `LedgerLab!2026` |
| `viewer@acme.test` | VIEWER | `LedgerLab!2026` |
| `admin@globex.test` | Other tenant | `LedgerLab!2026` |

A five-minute walkthrough is in [docs/demo-script.md](docs/demo-script.md). Sample settlement files are in [samples/](samples/).

## Tests

```bash
./scripts/test.sh          # backend verify + frontend lint/unit/build
./scripts/e2e.sh           # Playwright against real backend + Postgres
```

Backend tests start disposable PostgreSQL via Testcontainers. They do not need `docker compose` to already be running, but Docker itself must be available.

Acceptance coverage includes: balanced journals, immutable entries, idempotent retries and conflicts, concurrent capture/refund, rollback after injected failure, tenant isolation, viewer restrictions, reconciliation classifications, Flyway from an empty database.

## Design decisions

Documented in [docs/architecture.md](docs/architecture.md). Short version:

| Choice | Why |
|--------|-----|
| Integer minor units | USD only; no floating point anywhere on a money path |
| DB triggers + CHECKs | Application bugs cannot unbalance a journal or overdraw a customer |
| Pessimistic locks on payments | The contended resource is one payment; retries would be slower and racy |
| Idempotency inside the business transaction | Failed requests do not consume a key; concurrent duplicates serialize on a unique index |
| HS256 JWT | Single service; 15-minute tokens; no cookies, so CSRF is off |

## Repository layout

```text
backend/     Spring Boot API (com.ledgerlab.* by capability)
frontend/    React dashboard
docs/        architecture, domain, API, plan, demo, resume notes
samples/     settlement CSVs
scripts/     local run / test / EXPLAIN helpers
```

## Known limitations

- USD only. No refresh tokens. No real bank or processor.
- Dispute workflow is open → won/lost.
- JWT secret and demo password are well-known in the `dev`/`e2e` profiles only.

## License

Personal portfolio project. All rights reserved unless a LICENSE file is added.
