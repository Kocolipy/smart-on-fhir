#!/usr/bin/env bash
# The SPA–API contract check — the one gate that belongs to neither app. It holds
# the vocabulary the SPA mirrors by hand (PERMISSIONS, TOKEN_PERMISSIONS,
# AUDIT_OPERATIONS, LOCK_CAUSES, and the Permission or Superuser requirement
# of every request declaration) to backend/docs/openapi.yaml, which the
# backend's own contract tests hold to the running service.
#
# It lives here, not in either app, because it reads both: an app may not
# reach outside itself (root AGENTS.md, "Paths"). It runs on the frontend's
# pinned Node and node_modules, so `npm ci` in frontend/ comes first.
#
# Tests the check before trusting it: a check that cannot fail proves nothing.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_node
[[ -d $FRONTEND_DIR/node_modules ]] ||
  die "frontend/node_modules missing — run 'make bootstrap' (npm ci in frontend/) first."

log "testing the SPA–API contract check"
node --test --test-reporter=spec "$ROOT_DIR/scripts/spa-contract/check.test.mjs"

log "holding the SPA's mirrored vocabulary to backend/docs/openapi.yaml"
node "$ROOT_DIR/scripts/spa-contract/main.mjs" "$FRONTEND_DIR" "$BACKEND_DIR/docs/openapi.yaml"
