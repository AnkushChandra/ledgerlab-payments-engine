#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

docker compose -f "$root/docker-compose.yml" up -d --wait

(cd "$root/frontend" && npm ci && npx playwright install chromium && npm run build)

(cd "$root/backend" && ./mvnw -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=e2e) &
backend_pid=$!
cleanup() { kill "$backend_pid" 2>/dev/null || true; }
trap cleanup EXIT

for i in $(seq 1 60); do
  curl -sf http://localhost:8080/actuator/health/readiness >/dev/null && break
  sleep 2
done

cd "$root/frontend"
npx vite preview --port 4173 --strictPort --host 127.0.0.1 &
preview_pid=$!
trap 'kill "$backend_pid" "$preview_pid" 2>/dev/null || true' EXIT
sleep 2
npx playwright test
