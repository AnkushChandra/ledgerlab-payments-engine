#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

echo "== backend =="
(cd "$root/backend" && ./mvnw -B verify)

echo "== frontend =="
(cd "$root/frontend" && npm ci && npm run lint && npm run format:check && npm test && npm run build)

echo "All automated tests passed."
