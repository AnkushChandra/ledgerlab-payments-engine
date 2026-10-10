#!/usr/bin/env python3
"""Measure one fact against a running LedgerLab API.

N threads each try to capture the full authorized amount of one payment, with distinct
idempotency keys. A correct ledger lets exactly one capture succeed and keeps
captured_amount_minor equal to authorized_amount_minor.

Requires the API (profile dev or e2e) and its Postgres to already be running.
Prints a single JSON object on stdout. Exits 1 if the invariant fails.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get("LEDGERLAB_API", "http://localhost:8080").rstrip("/")
ATTEMPTS = int(os.environ.get("LEDGERLAB_CAPTURE_ATTEMPTS", "32"))
AMOUNT = 1_000  # $10.00


def request(method: str, path: str, token: str | None = None, body: dict | None = None, key: str | None = None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            payload = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            payload = raw.decode(errors="replace")
        return err.code, payload


def die(message: str) -> None:
    print(message, file=sys.stderr)
    sys.exit(2)


def main() -> None:
    status, login = request(
        "POST",
        "/api/v1/auth/login",
        body={"email": "ops@acme.test", "password": "LedgerLab!2026"},
    )
    if status != 200 or not isinstance(login, dict):
        die(f"login failed: {status} {login}")
    token = login["accessToken"]

    status, customers = request("GET", "/api/v1/accounts?type=CUSTOMER&size=20", token)
    status_m, merchants = request("GET", "/api/v1/accounts?type=MERCHANT&size=20", token)
    if status != 200 or status_m != 200:
        die(f"account lookup failed: customers={status} merchants={status_m}")
    customer = next(a for a in customers["items"] if a["status"] == "ACTIVE")
    merchant = next(a for a in merchants["items"] if a["status"] == "ACTIVE")

    if customer["availableMinor"] < AMOUNT:
        dep_status, dep = request(
            "POST",
            "/api/v1/deposits",
            token,
            {"accountId": customer["id"], "amountMinor": AMOUNT, "memo": "contention probe funding"},
            "probe-deposit-" + uuid.uuid4().hex,
        )
        if dep_status not in (200, 201):
            die(f"deposit failed: {dep_status} {dep}")

    auth_status, payment = request(
        "POST",
        "/api/v1/payments",
        token,
        {
            "customerAccountId": customer["id"],
            "merchantAccountId": merchant["id"],
            "amountMinor": AMOUNT,
            "reference": "PROBE-" + uuid.uuid4().hex[:8],
            "description": "concurrent capture probe",
        },
        "probe-auth-" + uuid.uuid4().hex,
    )
    if auth_status not in (200, 201) or payment.get("status") != "AUTHORIZED":
        die(f"authorize failed: {auth_status} {payment}")
    payment_id = payment["id"]

    def capture(i: int):
        code, payload = request(
            "POST",
            f"/api/v1/payments/{payment_id}/captures",
            token,
            {"amountMinor": AMOUNT, "finalCapture": True},
            f"probe-cap-{i:02d}-" + uuid.uuid4().hex,
        )
        problem = payload.get("code") if isinstance(payload, dict) else None
        return code, problem

    with ThreadPoolExecutor(max_workers=ATTEMPTS) as pool:
        results = list(pool.map(capture, range(ATTEMPTS)))

    final_status, final = request("GET", f"/api/v1/payments/{payment_id}", token)
    integ_status, integrity = request("GET", "/api/v1/ledger/integrity", token)
    if final_status != 200 or integ_status != 200:
        die(f"readback failed: payment={final_status} integrity={integ_status}")

    successes = sum(1 for code, _ in results if code in (200, 201))
    captured = final["capturedAmountMinor"]
    authorized = final["authorizedAmountMinor"]
    report = {
        "metric": "concurrent_full_captures",
        "api": BASE,
        "attempts": ATTEMPTS,
        "authorizedMinor": authorized,
        "successes": successes,
        "rejected": ATTEMPTS - successes,
        "outcomes": sorted({f"{code}:{problem or 'ok'}" for code, problem in results}),
        "capturedMinor": captured,
        "overCaptureMinor": captured - authorized,
        "unbalancedJournals": integrity["unbalancedTransactions"],
        "accountsWithBalanceDrift": integrity["accountsWithBalanceDrift"],
        "accountingEquationHolds": integrity["accountingEquationHolds"],
        "journals": integrity["transactionCount"],
        "entries": integrity["entryCount"],
    }
    print(json.dumps(report, indent=2))
    if successes != 1 or captured != authorized or integrity["unbalancedTransactions"] != 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
