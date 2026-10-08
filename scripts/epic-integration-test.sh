#!/usr/bin/env bash
# Epic Login's E2E conditional gate: a real SMART EHR launch, end to end, with
# the SMART Health IT launcher standing in for Epic (/docs/adr/0013-epic-login.md).
# frontend/AGENTS.md carries its trigger and when it passes.
#
# Brings up Postgres, Redis and the launcher (the compose `epic-launcher`
# profile), starts the backend in the dev profile with Epic Login pointed at the
# launcher, then runs the Playwright `epic` project, which starts the Vite dev
# server itself. Everything this script started is torn down again unless
# KEEP_UP=1 (stack_up and stack_e2e in lib.sh).
#
# The signing key is generated here, for this run only, and never written to
# disk: this repository is public, so no key is ever committed for it.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_cmd openssl
stack_up "Postgres, Redis and the SMART launcher" epic-launcher

# Where the browser and the backend reach the launcher — compose.yaml publishes
# it on 9009 — and so the issuer it reports: the launcher derives every URL it
# hands out from the request's Host.
LAUNCHER_URL="http://localhost:9009"

# The launcher's EHR launch names the FHIR base `/v/r4/fhir`, and that is also
# its OIDC issuer. Both are http, which only the dev profile accepts (D21); the
# dev profile also accepts the launcher's relative `fhirUser`.
export SPRING_PROFILES_ACTIVE=dev
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

# What the spec builds its launch from (frontend/README.md lists both). The
# launcher container fetches our JWKS from the host (compose.yaml maps
# host.docker.internal to it).
export E2E_EPIC_FHIR_BASE="$APP_EPIC_FHIR_BASE"
export E2E_EPIC_JWKS_URL="http://host.docker.internal:$BACKEND_PORT/api/auth/epic/jwks.json"
# The launcher's container, which the unavailable case pauses for one callback so
# our token call to it gets no answer (D23), and unpauses again.
E2E_EPIC_LAUNCHER_CONTAINER="$("${STACK_COMPOSE[@]}" ps -q smart-launcher)"
[[ -n $E2E_EPIC_LAUNCHER_CONTAINER ]] || die "the smart-launcher container is not running"
export E2E_EPIC_LAUNCHER_CONTAINER

stack_e2e test:e2e:epic
