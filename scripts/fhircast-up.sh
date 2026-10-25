#!/usr/bin/env bash
# `make fhircast-up`: the local Epic stand-in — Medplum and the DCR shim, the
# compose `fhircast` profile — with SQL Server and Redis, every service
# healthy, and Medplum seeded for this app: its ClientApplication, the
# clinician, Tan Wei Ming, and a SMART launch for him.
#
# It then keeps a managed block in backend/.env (backend/fhircast-shim/src/
# env-block.mjs) holding what points a backend run by `make dev` at the
# stand-in — the APP_EPIC_* settings, a signing key generated on the first run
# and kept, and the dev fixtures' clinician User — and prints the launch URL
# and the clinician to sign in as. It does not start the app: it warns, and says
# how, when Vite (:5173) or the backend (:8080) is not answering.
# backend/docs/configuration/epic-login.md, "Local Epic stand-in", has the
# rest; `make fhircast-down` stops it all again and leaves the block in place.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_docker
require_node

# Before any container starts, as `make infra-up` needs it: the managed block
# goes into the developer's own file, which this script never creates.
env_file="$BACKEND_DIR/.env"
[[ -f $env_file ]] ||
  die "no backend/.env — copy backend/.env.example to backend/.env first (as 'make infra-up' also needs), then run 'make fhircast-up' again."

compose=(docker compose -f "$BACKEND_DIR/compose.yaml" --profile fhircast)

log "starting SQL Server, Redis and the local Epic stand-in"
"${compose[@]}" up -d --wait
# As `make infra-up` does: the local database and its three logins.
"${compose[@]}" run --rm sqlserver-init
fhircast_seed compose

base_url="$(compose_env compose fhircast-shim SHIM_PUBLIC_BASE_URL)"
fhir_base="${base_url}fhir/R4"
clinician_id="$(compose_env compose medplum-init SEED_CLINICIAN_ID)"
clinician_email="$(compose_env compose medplum-init SEED_CLINICIAN_EMAIL)"
clinician_password="$(compose_env compose medplum-init SEED_CLINICIAN_PASSWORD)"
launch_uri="$(compose_env compose medplum-init SEED_CLIENT_LAUNCH_URI)"
launch_url="${launch_uri}?iss=${fhir_base}&launch=${FHIRCAST_LAUNCH_ID}"

# The fixtures' clinician User is named by the Practitioner id, which compose.yaml
# alone chooses (SEED_CLINICIAN_ID): the backend takes it from here rather than
# from a copy of its own.
log "keeping the managed block in backend/.env"
written="$(node "$BACKEND_DIR/fhircast-shim/src/write-env-block.mjs" "$env_file" \
  "SPRING_PROFILES_ACTIVE=local" \
  "APP_EPIC_ENABLED=true" \
  "APP_EPIC_FHIR_BASE=$fhir_base" \
  "APP_EPIC_OAUTH_ISSUER=${base_url%/}" \
  "APP_EPIC_CLIENT_ID=$FHIRCAST_CLIENT_ID" \
  "APP_EPIC_REDIRECT_URI=$(compose_env compose medplum-init SEED_CLIENT_REDIRECT_URIS | cut -d, -f1)" \
  "APP_DEV_FIXTURES_ENABLED=true" \
  "APP_DEV_FIXTURES_EPIC_CLINICIAN=$clinician_id")" ||
  die "could not write the managed block to backend/.env — see above."
block_changed="$(sed -n 's/^ENV_BLOCK_CHANGED=//p' <<<"$written")"
key_generated="$(sed -n 's/^ENV_BLOCK_KEY_GENERATED=//p' <<<"$written")"
if [[ $key_generated == true ]]; then
  log "generated the Epic signing key (an EC P-384 key, kept in the block from now on)"
fi

log "the local Epic stand-in is up"
cat <<EOF

  Launch URL: $launch_url
  Sign in on Medplum's page as:
    $clinician_email / $clinician_password

  The launch signs the clinician in to the app and opens Tan Wei Ming.
EOF

if [[ $block_changed == true ]]; then
  warn "backend/.env changed: restart the backend (stop 'make dev' and start it again) so it picks the block up."
fi

# A warning, never a failure: this script's job is the stand-in.
down=()
http_answers "http://localhost:5173" || down+=("Vite (:5173)")
http_answers "http://localhost:8080" || down+=("the backend (:8080)")
if ((${#down[@]} > 0)); then
  printf -v not_answering '%s, ' "${down[@]}"
  warn "nothing answers on ${not_answering%, }: start the app with 'make dev', then open $launch_url"
fi
