#!/usr/bin/env bash
# `make fhircast-up`: the local Epic stand-in — Medplum and the DCR shim,
# the compose `fhircast` profile — with SQL Server and Redis, every service
# healthy, Medplum seeded for this app, and then the APP_EPIC_* values that
# point a backend run by `make dev` at it.
# backend/docs/configuration/epic-login.md, "Local Epic stand-in", has the
# rest; `make fhircast-down` stops it all again.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
require_node

compose=(docker compose -f "$BACKEND_DIR/compose.yaml" --profile fhircast)

log "starting SQL Server, Redis and the local Epic stand-in"
"${compose[@]}" up -d --wait
# As `make infra-up` does: the local database and its three logins.
"${compose[@]}" run --rm sqlserver-init
fhircast_seed compose

base_url="$(compose_env compose fhircast-shim SHIM_PUBLIC_BASE_URL)"
log "the local Epic stand-in is up; run the backend with these in place of the Epic lines in .env:"
cat <<EOF
SPRING_PROFILES_ACTIVE=local,dev
APP_EPIC_ENABLED=true
APP_EPIC_FHIR_BASE=${base_url}fhir/R4
APP_EPIC_OAUTH_ISSUER=${base_url%/}
APP_EPIC_CLIENT_ID=$FHIRCAST_CLIENT_ID
APP_EPIC_REDIRECT_URI=$(compose_env compose medplum-init SEED_CLIENT_REDIRECT_URIS | cut -d, -f1)
APP_EPIC_CLIENT_KEY and APP_EPIC_CLIENT_KEY_ID: a key generated for the purpose

The clinician to sign in as on Medplum's page is the Practitioner
$(compose_env compose medplum-init SEED_CLINICIAN_ID) — provision a User with that userName over SCIM.
EOF
