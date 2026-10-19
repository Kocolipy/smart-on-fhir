#!/usr/bin/env bash
# The whole Playwright suite, end to end, in one run: every project in
# frontend/playwright.config.ts, Epic Login's (`epic`) and the packaged SPA's
# (`packaged`) among them. frontend/AGENTS.md carries its trigger and when it
# passes.
#
# Packages the JAR with the SPA (scripts/package.sh), brings up SQL Server, Redis
# and the SMART launcher standing in for Epic (the compose `epic-launcher`
# profile, /docs/adr/0012-epic-login.md), and runs the JAR with Epic Login
# pointed at the launcher. Playwright starts the Vite dev server itself, which
# proxies /api to the JAR: most projects run through it, and `packaged` loads
# the JAR's own origin, where the built bundle runs under the backend's CSP.
# Everything this script started is torn down again unless KEEP_UP=1 (stack_up
# and stack_e2e in lib.sh).
#
# The signing key is generated here, for this run only, and never written to
# disk: this repository is public, so no key is ever committed for it.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_cmd openssl

"$ROOT_DIR/scripts/package.sh"
# -print -quit rather than `find ... | head -1`: see package.sh.
jar="$(find "$BACKEND_DIR/target" -maxdepth 1 -name 'backend-*.jar' ! -name '*-plain.jar' -print -quit)"
[[ -n $jar ]] || die "no backend-*.jar in backend/target after packaging"

stack_up "SQL Server, Redis and the SMART launcher" epic-launcher

# Where the browser and the backend reach the launcher — compose.yaml publishes
# it on 9009 — and so the issuer it reports: the launcher derives every URL it
# hands out from the request's Host.
LAUNCHER_URL="http://localhost:9009"

# The launcher's EHR launch names the FHIR base `/v/r4/fhir`, and that is also
# its OIDC issuer. Both are http, which only the dev profile accepts (D21); the
# dev profile also accepts the launcher's relative `fhirUser`, and changes
# nothing outside Epic Login. `local` keeps the local run's in-process
# migrations, which every other profile turns off.
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

stack_e2e test:e2e java -jar "$jar"
