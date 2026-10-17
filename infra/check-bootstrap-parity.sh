#!/bin/bash
# The deployed database bootstrap and the local one run the same T-SQL: from
# `SET NOCOUNT ON;` down, /opt/backend/db-bootstrap.sql (infrastructure.yaml,
# the EC2 instance's AWS::CloudFormation::Init metadata) and
# backend/scripts/local-db-init.sql are the same lines. A stack cannot read a
# file from this repository, so the text is held twice and this check keeps
# the copies equal (infra/docs/database-boot.md, "Database bootstrap"). Exits non-zero, with
# the difference, when they are not.
#
# Usage: ./check-bootstrap-parity.sh [--extract <dir>]
#   --extract  also write the instance's db-bootstrap.sql, db-bootstrap.sh and
#              secret.sh (which db-bootstrap.sh sources), exactly as cfn-init
#              will, into <dir> (to run them elsewhere).
# deploy.sh runs it before every deploy.
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$INFRA_DIR/infrastructure.yaml"
LOCAL_SQL="$INFRA_DIR/../backend/scripts/local-db-init.sql"

# metadata_file <path>: the content of one cfn-init file of the template, its
# block scalar's indentation removed.
metadata_file() {
  awk -v path="            $1:" '
    $0 == path { inside = 1; next }
    inside && /^              content: \|$/ { body = 1; next }
    body && /^ {16}/ { print substr($0, 17); next }
    body && /^$/ { print ""; next }
    body { exit }
  ' "$TEMPLATE"
}

from_set_nocount() {
  sed -n '/^SET NOCOUNT ON;$/,$p'
}

deployed=$(metadata_file /opt/backend/db-bootstrap.sql)
[[ -n $deployed ]] || { echo "check-bootstrap-parity: no /opt/backend/db-bootstrap.sql in $TEMPLATE" >&2; exit 1; }

if [[ ${1:-} == --extract ]]; then
  [[ -n ${2:-} ]] || { echo "usage: $0 [--extract <dir>]" >&2; exit 1; }
  mkdir -p "$2"
  printf '%s\n' "$deployed" >"$2/db-bootstrap.sql"
  metadata_file /opt/backend/db-bootstrap.sh >"$2/db-bootstrap.sh"
  metadata_file /opt/backend/secret.sh >"$2/secret.sh"
fi

if ! diff -u --label "backend/scripts/local-db-init.sql" --label "infrastructure.yaml /opt/backend/db-bootstrap.sql" \
  <(from_set_nocount <"$LOCAL_SQL") <(printf '%s\n' "$deployed" | from_set_nocount); then
  echo "check-bootstrap-parity: the deployed and the local bootstrap SQL differ (above); change both together" >&2
  exit 1
fi
echo "check-bootstrap-parity: the deployed and the local bootstrap SQL agree"
