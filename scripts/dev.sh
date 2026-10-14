#!/usr/bin/env bash
# Run the backend and the frontend together with interleaved, prefixed logs.
# Ctrl-C stops both. The Vite dev server proxies /api to localhost:8080, so the
# SPA is served from http://localhost:5173 and talks to the live backend.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_node
require_maven
require_cmd curl

[[ -d $FRONTEND_DIR/node_modules ]] ||
  die "frontend/node_modules missing — run 'make bootstrap' first."

load_backend_env
BACKEND_PORT="${SERVER_PORT:-8080}"

# Both ports must be free before anything starts. A leftover process answering on
# 8080 would otherwise satisfy the readiness probe below, and you would be
# debugging a server this script never launched.
require_port_free "$BACKEND_PORT" "the backend"
require_port_free 5173 "the Vite dev server"

# Postgres and Redis are a hard dependency: the backend fails its datasource and
# Redis session setup without them. Checking here turns a silent multi-minute
# startup wait into an immediate, actionable message. Probe the addresses the
# backend itself will use, read from backend/.env, so the check agrees with the
# backend whether the dependencies sit in Docker or on the Windows host.
read -r pg_host pg_port < <(jdbc_host_port "${DATABASE_URL:-}")
for dep in "Postgres|$pg_host|$pg_port" "Redis|${REDIS_HOST:-localhost}|${REDIS_PORT:-6379}"; do
  IFS='|' read -r name host port <<<"$dep"
  port_reachable "$host" "$port" ||
    die "$name is not reachable at $host:$port (backend/.env) — run 'make infra-up' first."
done

backend_pid=""
frontend_pid=""

cleanup() {
  trap - INT TERM EXIT
  log "stopping dev processes"
  stop_group "$frontend_pid"
  stop_group "$backend_pid"
}
trap cleanup INT TERM EXIT

bg_start backend_pid backend "$BACKEND_DIR" "$MVN" -q spring-boot:run
wait_for_http "http://localhost:$BACKEND_PORT/actuator/health" backend 180 "$backend_pid"

bg_start frontend_pid frontend "$FRONTEND_DIR" "$NPM" run dev
wait_for_http "http://localhost:5173" frontend 120 "$frontend_pid"

log "backend http://localhost:$BACKEND_PORT — frontend http://localhost:5173 (Ctrl-C stops both)"

# Return as soon as either side dies, so a crashed backend does not leave a
# frontend running against nothing. Both are real children of this shell, which
# is what makes this wait — and the group kill in cleanup — work at all.
wait -n "$backend_pid" "$frontend_pid" || true
warn "one of the two processes exited — shutting the other down"
