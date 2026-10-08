# Sample settlement files

These files simulate a card processor's daily settlement report. They reference the payments in the
demo seed (`backend/src/main/resources/db/seed/R__demo_seed.sql`, organization **Acme Payments**), so
log in as `ops@acme.test` and upload them with period **2026-09-01 → 2026-09-03**.

## Format

UTF-8 CSV, maximum 1 MB and 10,000 records. The header must be exactly:

```text
processor_record_id,payment_id,status,amount_minor,currency,settled_at
```

| Column | Rules |
|--------|-------|
| `processor_record_id` | 1–64 chars of `[A-Za-z0-9._-]`, unique within the file |
| `payment_id` | LedgerLab payment UUID (the processor echoes our reference) |
| `status` | The processor's view of the payment, using LedgerLab statuses (`CAPTURED`, `PARTIALLY_REFUNDED`, `REFUNDED`, …) |
| `amount_minor` | Net settled amount in cents: captured − refunded − charged back. Integer ≥ 0 |
| `currency` | `USD` |
| `settled_at` | ISO-8601 timestamp with offset, e.g. `2026-09-02T02:00:00Z` |

If any row is invalid the whole file is rejected (HTTP 422) with row-level errors; nothing is stored.
Uploading identical content twice is rejected with HTTP 409 and the id of the existing batch.

## Files

| File | Expected result (against fresh seed data) |
|------|-------------------------------------------|
| `settlement-matched.csv` | 5 × `MATCHED`, no exceptions |
| `settlement-mismatches.csv` | 2 × `MATCHED`, 1 × `AMOUNT_MISMATCH` (ORDER-1002 reported as 25,000 instead of 20,000), 1 × `STATUS_MISMATCH` (ORDER-1004 reported as CAPTURED but refunded internally), 1 × `DUPLICATE_EXTERNAL` (ORDER-1007 reported twice), 1 × `MISSING_INTERNAL` (unknown payment id), 1 × `MISSING_EXTERNAL` (ORDER-1006 captured but not reported) |
| `settlement-malformed.csv` | Rejected with errors on rows 2–5 (bad UUID; bad status, amount, currency and timestamp; duplicate record id; missing columns) |

Each file can be imported once per database because duplicate content is rejected. Reset the local
database with `docker compose down -v` to replay the demo.

If you change seeded payments (for example by opening a dispute on ORDER-1006 before uploading), the
classifications change accordingly; that is the reconciliation working as intended.
