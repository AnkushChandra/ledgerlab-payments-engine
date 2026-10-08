#!/usr/bin/env bash
# Prints EXPLAIN plans for the hottest list queries against the local Docker Postgres.
# Observed plans belong in docs/resume-notes.md only after this script is run.
set -euo pipefail

psql_cmd=(docker compose exec -T postgres psql -U ledgerlab -d ledgerlab -c)

echo "== payment list (org + created_at) =="
"${psql_cmd[@]}" "EXPLAIN (FORMAT TEXT) SELECT id FROM payment WHERE organization_id = '10000000-0000-4000-8000-000000000001' ORDER BY created_at DESC, id DESC LIMIT 20;"

echo "== ledger entries for an account =="
"${psql_cmd[@]}" "EXPLAIN (FORMAT TEXT) SELECT e.id FROM ledger_entry e JOIN ledger_account a ON a.id = e.ledger_account_id WHERE a.organization_id = '10000000-0000-4000-8000-000000000001' AND a.financial_account_id = '30000000-0000-4000-8000-000000000001' ORDER BY e.created_at DESC, e.id DESC LIMIT 15;"

echo "== audit events =="
"${psql_cmd[@]}" "EXPLAIN (FORMAT TEXT) SELECT id FROM audit_event WHERE organization_id = '10000000-0000-4000-8000-000000000001' ORDER BY occurred_at DESC, id DESC LIMIT 25;"

echo "== captured payments in a settlement period =="
"${psql_cmd[@]}" "EXPLAIN (FORMAT TEXT) SELECT id FROM payment WHERE organization_id = '10000000-0000-4000-8000-000000000001' AND captured_amount_minor > 0 AND first_captured_at >= '2026-09-01' AND first_captured_at < '2026-09-04';"
