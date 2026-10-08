#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

echo "Starting PostgreSQL on localhost:5433…"
docker compose -f "$root/docker-compose.yml" up -d --wait

echo "Starting backend (profile=dev) on :8080…"
(cd "$root/backend" && ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dev) &
backend_pid=$!

cleanup() {
  kill "$backend_pid" 2>/dev/null || true
}
trap cleanup EXIT

echo "Waiting for backend…"
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/actuator/health/readiness >/dev/null; then
    break
  fi
  sleep 2
done

echo "Starting frontend on :5173…"
cd "$root/frontend"
npm run dev
