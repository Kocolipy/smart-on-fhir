#!/usr/bin/env bash
# The whole Playwright suite, end to end, in one run: every project in
# frontend/playwright.config.ts, Epic Login's (`epic`), the local FHIRcast
# stand-in's (`fhircast`) and the packaged SPA's (`packaged`) among them.
# frontend/AGENTS.md carries its trigger and when it passes.
#
# Runs the FHIRcast DCR shim's unit tests first (backend/fhircast-shim), then
# packages the JAR with the SPA (scripts/package.sh), brings up SQL Server,
# Redis, the SMART launcher standing in for Epic (the compose `epic-launcher`
# profile, /docs/adr/0012-epic-login.md) and the local FHIRcast stand-in —
# Medplum and the DCR shim (the `fhircast` profile) — and seeds Medplum. It
# runs the JAR twice, on the one database and Redis: on 8080 with Epic Login
# pointed at the launcher, and on 8081 with Epic Login pointed at Medplum.
# Playwright starts the Vite dev server itself, which proxies /api to the 8080
# JAR: most projects run through it, `packaged` loads that JAR's own origin,
# where the built bundle runs under the backend's CSP, and `fhircast` loads the
# 8081 JAR's. Everything this script started is torn down again unless
# KEEP_UP=1 (stack_up and stack_backend in lib.sh).
#
# The signing keys are generated here, for this run only, and never written to
# disk: this repository is public, so no key is ever committed for it.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_cmd openssl
require_node

# First and cheapest: the stand-in the `fhircast` project runs against. No
# dependencies to install; it is Node's own test runner on the pinned Node.
log "testing the FHIRcast DCR shim"
node --test "$BACKEND_DIR"/fhircast-shim/test/*.test.mjs

"$ROOT_DIR/scripts/package.sh"
# -print -quit rather than `find ... | head -1`: see package.sh.
jar="$(find "$BACKEND_DIR/target" -maxdepth 1 -name 'backend-*.jar' ! -name '*-plain.jar' -print -quit)"
[[ -n $jar ]] || die "no backend-*.jar in backend/target after packaging"

# The second JAR's port, and what compose seeds Medplum with for it: its JWKS
# as Medplum's container reaches the host, its callback and launch routes, and
# hub tokens of about a minute, so the specs that need one can outlive it.
FHIRCAST_BACKEND_PORT=8081
export FHIRCAST_BACKEND_JWKS_URI="http://host.docker.internal:$FHIRCAST_BACKEND_PORT/api/auth/epic/jwks.json"
export FHIRCAST_APP_REDIRECT_URIS="http://localhost:$FHIRCAST_BACKEND_PORT/api/auth/epic/callback"
export FHIRCAST_APP_LAUNCH_URI="http://localhost:$FHIRCAST_BACKEND_PORT/api/auth/epic/launch"
export FHIRCAST_HUB_TOKEN_LIFETIME_SECONDS=60

stack_up "SQL Server, Redis, the SMART launcher and the FHIRcast stand-in" epic-launcher fhircast
require_port_free "$FHIRCAST_BACKEND_PORT" "the FHIRcast backend"
fhircast_seed STACK_COMPOSE

# Where the browser and the backend reach the launcher — compose.yaml publishes
# it on 9009 — and so the issuer it reports: the launcher derives every URL it
# hands out from the request's Host.
LAUNCHER_URL="http://localhost:9009"

# The launcher's EHR launch names the FHIR base `/v/r4/fhir`, and that is also
# its OIDC issuer. Both are http, which only the dev profile accepts (D21); the
# dev profile also accepts the launcher's relative `fhirUser`, and changes
# nothing outside Epic Login. `local` keeps the local run's in-process
# migrations, which every other profile turns off. The 8081 JAR needs both for
# the same reasons: Medplum is http too, and its `fhirUser` is relative.
export SPRING_PROFILES_ACTIVE=local,dev
export APP_EPIC_ENABLED=true
export APP_EPIC_FHIR_BASE="$LAUNCHER_URL/v/r4/fhir"
export APP_EPIC_OAUTH_ISSUER="$APP_EPIC_FHIR_BASE"
# The launcher accepts any client id unless a launch pins one.
export APP_EPIC_CLIENT_ID=epic-e2e
# Through the Vite proxy, so the callback's `302 /` lands on the SPA.
export APP_EPIC_REDIRECT_URI="http://localhost:5173/api/auth/epic/callback"
APP_EPIC_CLIENT_KEY="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384)"
export APP_EPIC_CLIENT_KEY
export APP_EPIC_CLIENT_KEY_ID="e2e-$(date +%s)"
unset APP_EPIC_CLIENT_NEXT_KEY APP_EPIC_CLIENT_NEXT_KEY_ID

# What the Epic spec builds its launch from (frontend/README.md lists each); a
# set E2E_EPIC_FHIR_BASE is also what declares the `epic` project. The launcher
# container fetches our JWKS from the host (compose.yaml maps
# host.docker.internal to it).
export E2E_EPIC_FHIR_BASE="$APP_EPIC_FHIR_BASE"
export E2E_EPIC_JWKS_URL="http://host.docker.internal:$BACKEND_PORT/api/auth/epic/jwks.json"
# The launcher's container, which the unavailable case pauses for one callback so
# our token call to it gets no answer (D23), and unpauses again.
E2E_EPIC_LAUNCHER_CONTAINER="$("${STACK_COMPOSE[@]}" ps -q smart-launcher)"
[[ -n $E2E_EPIC_LAUNCHER_CONTAINER ]] || die "the smart-launcher container is not running"
export E2E_EPIC_LAUNCHER_CONTAINER

# The JAR's own origin, which declares the `packaged` project.
export E2E_PACKAGED_BASE_URL="http://localhost:$BACKEND_PORT"

# Medplum, as the shim answers for it (its base URL ends in a slash), and the
# APP_EPIC_* values backend/docs/configuration/epic-login.md, "Local FHIRcast
# stand-in", lists. The issuer is the base URL without its slash: the backend
# discovers it at {issuer}/.well-known/openid-configuration.
MEDPLUM_URL="$(compose_env STACK_COMPOSE fhircast-shim SHIM_PUBLIC_BASE_URL)"
FHIRCAST_FHIR_BASE="${MEDPLUM_URL}fhir/R4"
FHIRCAST_CLIENT_KEY="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384)"

# What the FHIRcast specs need (frontend/README.md lists each); a set
# E2E_FHIRCAST_BASE_URL, the 8081 JAR's origin, is what declares the `fhircast`
# project. The sign-ins are the development fixtures compose.yaml seeds.
export E2E_FHIRCAST_BASE_URL="http://localhost:$FHIRCAST_BACKEND_PORT"
export E2E_FHIRCAST_MEDPLUM_URL="$MEDPLUM_URL"
export E2E_FHIRCAST_FHIR_BASE="$FHIRCAST_FHIR_BASE"
export E2E_FHIRCAST_PRACTITIONER_ID="$FHIRCAST_PRACTITIONER_ID"
E2E_FHIRCAST_ADMIN_EMAIL="$(compose_env STACK_COMPOSE fhircast-shim SHIM_MEDPLUM_ADMIN_EMAIL)"
E2E_FHIRCAST_ADMIN_PASSWORD="$(compose_env STACK_COMPOSE fhircast-shim SHIM_MEDPLUM_ADMIN_PASSWORD)"
E2E_FHIRCAST_CLINICIAN_EMAIL="$(compose_env STACK_COMPOSE medplum-init SEED_CLINICIAN_EMAIL)"
E2E_FHIRCAST_CLINICIAN_PASSWORD="$(compose_env STACK_COMPOSE medplum-init SEED_CLINICIAN_PASSWORD)"
export E2E_FHIRCAST_ADMIN_EMAIL E2E_FHIRCAST_ADMIN_PASSWORD E2E_FHIRCAST_CLINICIAN_EMAIL E2E_FHIRCAST_CLINICIAN_PASSWORD

stack_backend backend "$BACKEND_PORT" java -jar "$jar"
# The same JAR again, with Epic Login at Medplum instead: its redirect URI is
# its own origin's, as the Vite proxy reaches the 8080 JAR alone.
stack_backend fhircast-backend "$FHIRCAST_BACKEND_PORT" env \
  SERVER_PORT="$FHIRCAST_BACKEND_PORT" \
  APP_EPIC_FHIR_BASE="$FHIRCAST_FHIR_BASE" \
  APP_EPIC_OAUTH_ISSUER="${MEDPLUM_URL%/}" \
  APP_EPIC_CLIENT_ID="$FHIRCAST_CLIENT_ID" \
  APP_EPIC_REDIRECT_URI="http://localhost:$FHIRCAST_BACKEND_PORT/api/auth/epic/callback" \
  APP_EPIC_CLIENT_KEY="$FHIRCAST_CLIENT_KEY" \
  APP_EPIC_CLIENT_KEY_ID="e2e-fhircast-$(date +%s)" \
  java -jar "$jar"

stack_e2e test:e2e
