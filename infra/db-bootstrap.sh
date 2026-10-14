#!/bin/bash
# Creates the database logins on a stack's RDS instance — the owner, app and
# retention logins, each with the password its Secrets Manager secret holds —
# and hands the database to the owner login. Re-runnable: it creates what is
# missing and resets each login's password to its secret's.
#
# Runs on the operator's machine, with the operator's AWS credentials, because
# it reads the RDS-managed master secret, and the instance role cannot (ADR
# 0015, as-8). psql connects through an SSH tunnel to the instance, with TLS
# from this psql to RDS end to end, so nothing database-related runs on the
# instance and no credential is sent to it. See infra/README.md, "Database
# credentials".
#
# Usage: ./db-bootstrap.sh <stack-name> [region]
#   KEY_FILE  the instance's SSH key (default: <KeyPairName>.pem beside this script)
#
# Never run with command tracing (bash -x): see lib.sh.
set -euo pipefail

# shellcheck source=lib.sh source-path=SCRIPTDIR
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

main() {
  local stack=${1:-} region=${2:-ap-southeast-1} json login password
  [[ -n $stack ]] || die "usage: $0 <stack-name> [region]"
  require_cmd aws
  require_cmd jq
  require_cmd curl
  require_cmd ssh
  require_cmd psql "the PostgreSQL client, 15 or newer"
  require_psql_getenv

  load_stack_outputs "$stack" "$region"

  json=$(secret_string "$(stack_output DBMasterSecretArn)" "$region")
  MASTER_USER=$(json_field "$json" username)
  MASTER_PASSWORD=$(json_field "$json" password)
  json=$(secret_string "$(stack_output DBOwnerSecretArn)" "$region")
  OWNER_LOGIN=$(json_field "$json" username)
  OWNER_PASSWORD=$(json_field "$json" password)
  json=$(secret_string "$(stack_output DBAppSecretArn)" "$region")
  APP_LOGIN=$(json_field "$json" username)
  APP_PASSWORD=$(json_field "$json" password)
  json=$(secret_string "$(stack_output DBRetentionSecretArn)" "$region")
  RETENTION_LOGIN=$(json_field "$json" username)
  RETENTION_PASSWORD=$(json_field "$json" password)
  json=""

  require_login_name "the owner login" "$OWNER_LOGIN"
  require_login_name "the app login" "$APP_LOGIN"
  require_login_name "the retention login" "$RETENTION_LOGIN"

  trap close_stack_db_tunnel EXIT
  open_stack_db_tunnel "$region"

  print_info "creating the database logins as the RDS master user (over verify-full TLS)"
  run_db_bootstrap "$MASTER_USER" "$MASTER_PASSWORD" >/dev/null

  for login in "$OWNER_LOGIN" "$APP_LOGIN" "$RETENTION_LOGIN"; do
    case $login in
      "$OWNER_LOGIN") password=$OWNER_PASSWORD ;;
      "$APP_LOGIN") password=$APP_PASSWORD ;;
      *) password=$RETENTION_PASSWORD ;;
    esac
    [[ $(psql_as "$login" "$password" -tAc 'SELECT current_user') == "$login" ]] ||
      die "$login cannot sign in with its secret's password"
    print_info "$login signs in with its secret's password"
  done
  print_info "database logins ready on stack $stack"
}

main "$@"
