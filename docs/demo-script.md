# Five-minute LedgerLab demo

Local demo credentials (development data only, never production):

| Email | Role | Password |
|-------|------|----------|
| `ops@acme.test` | OPERATIONS | `LedgerLab!2026` |
| `admin@acme.test` | ADMIN | `LedgerLab!2026` |
| `viewer@acme.test` | VIEWER | `LedgerLab!2026` |
| `admin@globex.test` | ADMIN of a second tenant | `LedgerLab!2026` |

Open http://localhost:5173 after `./scripts/dev.sh`.

1. **Sign in as operations** (`ops@acme.test`). Point at the overview: customer available, reserved authorizations, merchant payable, dispute holds, and the ledger integrity card. The integrity check re-derives every cached balance from raw entries and confirms the accounting equation (`clearing asset = total liabilities`).

2. **Open Ada Lovelace**. Show the ledger statement: deposits, authorizations and captures as signed entries with running balances. Click **Journal** on any row and show that debits equal credits.

3. **Authorize a new payment** from Ada to Blue Bottle Coffee for `$15.00`, then **Capture** `$10.00` as a final capture. The remainder returns to Ada; Blue Bottle's available balance increases by `$10.00`. Open the payment timeline.

4. **Refund** `$3.00`. Show the payment moving to `PARTIALLY_REFUNDED` and both account balances reversing.

5. **Open a dispute** for the remaining `$7.00`. Merchant available drops and dispute hold rises. Resolve it as **Lost**: the customer is made whole, the merchant hold is gone, the payment is `RESOLVED`.

6. **Reconciliation.** Upload `samples/settlement-mismatches.csv` with period `2026-09-01` → `2026-09-03`. Walk through amount mismatch, status mismatch, duplicate external, missing internal and missing external.

7. **Tenant and role isolation.** Sign out. Sign in as `viewer@acme.test`: capture/void/refund/upload buttons are gone. Sign in as `admin@globex.test`: Acme's accounts and payments are not listed (the API would return 404 if you guessed an id).
