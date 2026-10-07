#!/usr/bin/env bash
# Full-stack integration run: dependencies + both applications + Playwright.
#
# Playwright's own `webServer` block starts the Vite dev server (baseURL
# http://localhost:5173) and Vite proxies /api to localhost:8080, so this script
# brings up Postgres, Redis and the backend, then hands the frontend to
# Playwright. Everything this script started is torn down again unless KEEP_UP=1
# (stack_up and stack_e2e in lib.sh).
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

stack_up "Postgres and Redis"
stack_e2e test:e2e
