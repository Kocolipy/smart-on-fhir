#!/bin/bash
# Re-runs a stack's database bootstrap — the application database with its
# collation and isolation options, and the owner, app and retention logins —
# and reports whether the run changed anything.
#
# The bootstrap itself runs on the instance, as root, as the RDS master user:
# UserData runs it at boot, every migrate step runs it first, and this script
# runs it on demand (backend-db-bootstrap.service, /opt/backend/db-bootstrap.sh;
# infra/README.md, "Database bootstrap"). It is idempotent: on a bootstrapped
# stack a run changes nothing, and with --expect-no-op this script fails if it
# did. A login whose password no longer matches its secret is reset; that is the
# one change a re-run on a healthy stack can make.
#
# No database credential passes through this machine or this script: the
# instance reads the secrets itself, and its output holds none.
#
# Usage: ./db-bootstrap.sh [--expect-no-op] <stack-name> [region]
#   KEY_FILE  the instance's SSH key (default: <KeyPairName>.pem beside this script)
#
# Never run with command tracing (bash -x): see lib.sh.
set -euo pipefail

# shellcheck source=lib.sh source-path=SCRIPTDIR
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

main() {
  local expect_no_op="" stack region
  if [[ ${1:-} == --expect-no-op ]]; then
    expect_no_op=yes
    shift
  fi
  stack=${1:-}
  region=${2:-ap-southeast-1}
  [[ -n $stack ]] || die "usage: $0 [--expect-no-op] <stack-name> [region]"
  require_cmd aws
  require_cmd jq
  require_cmd ssh

  load_stack_outputs "$stack" "$region"
  wait_for_boot
  print_info "re-running the database bootstrap on stack $stack"
  rerun_db_bootstrap
  if ((DB_BOOTSTRAP_CHANGES == 0)); then
    print_info "the re-run was a no-op: the database and its logins were already in place"
  elif [[ -n $expect_no_op ]]; then
    die "the re-run changed $DB_BOOTSTRAP_CHANGES thing(s) on a bootstrapped stack (above)"
  else
    print_warn "the re-run made $DB_BOOTSTRAP_CHANGES change(s) (above)"
  fi
}

main "$@"
