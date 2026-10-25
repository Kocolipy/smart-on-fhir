#!/usr/bin/env bash
# The whole Playwright suite, end to end, in one run: every project in
# frontend/playwright.config.ts, Epic Login's (`epic`), the local Epic
# stand-in's own (`fhircast`) and the packaged SPA's (`packaged`) among them.
# frontend/AGENTS.md carries its trigger and when it passes.
#
# Runs the FHIRcast DCR shim's unit tests first (backend/fhircast-shim), then
# packages the JAR with the SPA (scripts/package.sh), brings up SQL Server,
# Redis and the local Epic stand-in — Medplum and the DCR shim, the compose
# `fhircast` profile (/docs/adr/0012-epic-login.md) — and seeds Medplum. It
# runs the JAR once, with Epic Login pointed at Medplum. Playwright starts the
# Vite dev server itself, which proxies /api to that JAR: every project but
# `packaged` runs through it, `epic` and `fhircast` among them, and `packaged`
# loads the JAR's own origin, where the built bundle runs under the backend's
# CSP. Everything this script started is torn down again unless KEEP_UP=1
# (stack_up and stack_backend in lib.sh).
#
# The signing keys are generated here, for this run only, and never written to
# disk: this repository is public, so no key is ever committed for it.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_cmd openssl
require_node

# First and cheapest: the stand-in `epic` and `fhircast` run against. No
# dependencies to install; it is Node's own test runner on the pinned Node.
log "testing the FHIRcast DCR shim"
node --test "$BACKEND_DIR"/fhircast-shim/test/*.test.mjs

"$ROOT_DIR/scripts/package.sh"
# -print -quit rather than `find ... | head -1`: see package.sh.
jar="$(find "$BACKEND_DIR/target" -maxdepth 1 -name 'backend-*.jar' ! -name '*-plain.jar' -print -quit)"
[[ -n $jar ]] || die "no backend-*.jar in backend/target after packaging"

# Hub tokens and subscription leases of about a minute, so the specs that need
# one can outlive it: 75 s, so that the SPA renews them 60 s before they end
# rather than as soon as it may. Set before the stand-in starts: compose hands
# both to the shim then.
export FHIRCAST_HUB_TOKEN_LIFETIME_SECONDS=75
export FHIRCAST_LEASE_SECONDS=75

stack_up "SQL Server, Redis and the local Epic stand-in" fhircast

# What the seed registers this app's ClientApplication with: our JWKS as
# Medplum's container reaches the backend on the host (compose.yaml maps
# host.docker.internal to it), and the callback and launch routes through the
# Vite proxy, so the callback's `302 /` lands on the SPA.
export FHIRCAST_BACKEND_JWKS_URI="http://host.docker.internal:$BACKEND_PORT/api/auth/epic/jwks.json"
export FHIRCAST_APP_REDIRECT_URIS="http://localhost:5173/api/auth/epic/callback"
export FHIRCAST_APP_LAUNCH_URI="http://localhost:5173/api/auth/epic/launch"
fhircast_seed STACK_COMPOSE

# Medplum, as the shim answers for it (its base URL ends in a slash), and the
# APP_EPIC_* values backend/docs/configuration/epic-login.md, "Local Epic
# stand-in", lists. The issuer is the base URL without its slash: the backend
# discovers it at {issuer}/.well-known/openid-configuration.
MEDPLUM_URL="$(compose_env STACK_COMPOSE fhircast-shim SHIM_PUBLIC_BASE_URL)"

# Medplum's FHIR base and issuer are http, which only the `local` profile
# accepts (D21); `local` also accepts Medplum's relative `fhirUser`, and keeps
# the local run's in-process migrations, which every other profile turns off.
export SPRING_PROFILES_ACTIVE=local
export APP_EPIC_ENABLED=true
export APP_EPIC_FHIR_BASE="${MEDPLUM_URL}fhir/R4"
export APP_EPIC_OAUTH_ISSUER="${MEDPLUM_URL%/}"
export APP_EPIC_CLIENT_ID="$FHIRCAST_CLIENT_ID"
export APP_EPIC_REDIRECT_URI="${FHIRCAST_APP_REDIRECT_URIS%%,*}"
APP_EPIC_CLIENT_KEY="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384)"
export APP_EPIC_CLIENT_KEY
export APP_EPIC_CLIENT_KEY_ID="e2e-$(date +%s)"
unset APP_EPIC_CLIENT_NEXT_KEY APP_EPIC_CLIENT_NEXT_KEY_ID
# The managed block `make fhircast-up` keeps in backend/.env names the seeded
# clinician for the dev fixtures, which would provision it as a User at startup.
# The E2E backend must not: `epic-unprovisioned` signs in as a clinician no User
# exists for, and the `fhircast` project provisions its own over SCIM. Unset
# after load_backend_env, whatever .env says.
unset APP_DEV_FIXTURES_EPIC_CLINICIAN

# What the `epic` and `fhircast` specs need (frontend/README.md lists each); a
# set E2E_FHIRCAST_FHIR_BASE, the launch `iss`, is what declares both
# projects. The sign-ins are the development fixtures compose.yaml seeds.
export E2E_FHIRCAST_MEDPLUM_URL="$MEDPLUM_URL"
export E2E_FHIRCAST_FHIR_BASE="$APP_EPIC_FHIR_BASE"
E2E_FHIRCAST_PRACTITIONER_ID="$(compose_env STACK_COMPOSE medplum-init SEED_CLINICIAN_ID)"
export E2E_FHIRCAST_PRACTITIONER_ID
E2E_EPIC_CLINICIANS="$(compose_env STACK_COMPOSE medplum-init SEED_EPIC_LOGIN_CLINICIANS)"
export E2E_EPIC_CLINICIANS
E2E_FHIRCAST_ADMIN_EMAIL="$(compose_env STACK_COMPOSE fhircast-shim SHIM_MEDPLUM_ADMIN_EMAIL)"
E2E_FHIRCAST_ADMIN_PASSWORD="$(compose_env STACK_COMPOSE fhircast-shim SHIM_MEDPLUM_ADMIN_PASSWORD)"
E2E_FHIRCAST_CLINICIAN_EMAIL="$(compose_env STACK_COMPOSE medplum-init SEED_CLINICIAN_EMAIL)"
E2E_FHIRCAST_CLINICIAN_PASSWORD="$(compose_env STACK_COMPOSE medplum-init SEED_CLINICIAN_PASSWORD)"
export E2E_FHIRCAST_ADMIN_EMAIL E2E_FHIRCAST_ADMIN_PASSWORD E2E_FHIRCAST_CLINICIAN_EMAIL E2E_FHIRCAST_CLINICIAN_PASSWORD

# The shim's container, which fronts Medplum's token endpoint: the Epic spec's
# unavailable case pauses it for one callback so our token call gets no answer
# (D23), and unpauses it again.
E2E_EPIC_SHIM_CONTAINER="$("${STACK_COMPOSE[@]}" ps -q fhircast-shim)"
[[ -n $E2E_EPIC_SHIM_CONTAINER ]] || die "the fhircast-shim container is not running"
export E2E_EPIC_SHIM_CONTAINER

# The JAR's own origin, which declares the `packaged` project.
export E2E_PACKAGED_BASE_URL="http://localhost:$BACKEND_PORT"

stack_backend backend "$BACKEND_PORT" java -jar "$jar"

stack_e2e test:e2e
