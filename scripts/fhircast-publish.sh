#!/usr/bin/env bash
# `make fhircast-publish EVENT=<Patient-open|Patient-close> PATIENT=<id>`, or
# `scripts/fhircast-publish.sh <event> <patient id>`: publishes a FHIRcast event
# to the local Epic stand-in's hub, as the EHR does when the clinician opens or
# closes a chart, so a FHIRcast card connected through `make fhircast-up`'s
# launch shows it.
#
# Everything else comes from backend/compose.yaml: the hub, Medplum's super
# admin, who publishes, and the seeded clinician, whose topic it publishes on
# (backend/fhircast-shim/src/publish.mjs has why). TOPIC=<topic> publishes on
# another, such as the `hubTopic` GET /api/auth/epic/fhircast releases to a
# session signed in as one of the E2E clinicians.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

(($# == 2)) && [[ -n $1 && -n $2 ]] ||
  die "usage: make fhircast-publish EVENT=<Patient-open|Patient-close> PATIENT=<patient id> [TOPIC=<topic>]"

require_docker
require_node

compose=(docker compose -f "$BACKEND_DIR/compose.yaml" --profile fhircast)
base_url="$(compose_env compose fhircast-shim SHIM_PUBLIC_BASE_URL)"
http_answers "${base_url}healthcheck" ||
  die "nothing answers at $base_url — start the local Epic stand-in with 'make fhircast-up' first."

PUBLISH_MEDPLUM_URL="$base_url" \
  PUBLISH_ADMIN_EMAIL="$(compose_env compose fhircast-shim SHIM_MEDPLUM_ADMIN_EMAIL)" \
  PUBLISH_ADMIN_PASSWORD="$(compose_env compose fhircast-shim SHIM_MEDPLUM_ADMIN_PASSWORD)" \
  PUBLISH_CLINICIAN_EMAIL="$(compose_env compose medplum-init SEED_CLINICIAN_EMAIL)" \
  PUBLISH_CLINICIAN_PASSWORD="$(compose_env compose medplum-init SEED_CLINICIAN_PASSWORD)" \
  PUBLISH_TOPIC="${TOPIC:-}" \
  node "$BACKEND_DIR/fhircast-shim/src/publish.mjs" "$1" "$2"
