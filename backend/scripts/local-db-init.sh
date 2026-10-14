#!/usr/bin/env bash
# The one-shot init of the local SQL Server: runs inside compose.yaml's
# `sqlserver-init` service once `sqlserver` is healthy, and creates the database
# with its creation options and the three logins from backend/.env
# (local-db-init.sql). Idempotent, so every `make infra-up` runs it again and a
# changed .env is applied.
#
# No secret is ever echoed or put on a command line: there is no `set -x`, sqlcmd
# runs without `-e`, the sa password reaches it as SQLCMDPASSWORD, and every other
# value as a scripting variable sqlcmd reads from the environment. The values are
# checked here before sqlcmd runs, so the script never puts a malformed name or a
# quote into T-SQL.
set -euo pipefail

die() {
  printf 'local-db-init: %s\n' "$*" >&2
  exit 1
}

for variable in MSSQL_SA_PASSWORD DATABASE_NAME DATABASE_OWNER_USERNAME DATABASE_OWNER_PASSWORD \
  DATABASE_USERNAME DATABASE_PASSWORD DATABASE_RETENTION_USERNAME DATABASE_RETENTION_PASSWORD; do
  [[ -n ${!variable:-} ]] || die "$variable is not set; copy it from .env.example into backend/.env"
done
[[ $DATABASE_NAME =~ ^[a-z][a-z0-9_]{0,63}$ ]] || die "DATABASE_NAME must match ^[a-z][a-z0-9_]{0,63}\$"
for login in "$DATABASE_OWNER_USERNAME" "$DATABASE_USERNAME" "$DATABASE_RETENTION_USERNAME"; do
  [[ $login =~ ^[a-z][a-z0-9_]{2,30}$ ]] || die "a login name must match ^[a-z][a-z0-9_]{2,30}\$"
done
[[ $DATABASE_OWNER_USERNAME != "$DATABASE_USERNAME" && $DATABASE_OWNER_USERNAME != "$DATABASE_RETENTION_USERNAME" &&
  $DATABASE_USERNAME != "$DATABASE_RETENTION_USERNAME" ]] ||
  die "the owner, app and retention logins must be three different logins"
for password in "$DATABASE_OWNER_PASSWORD" "$DATABASE_PASSWORD" "$DATABASE_RETENTION_PASSWORD"; do
  [[ $password != *"'"* && $password != *'$('* ]] || die "a database password must hold no quote and no \$("
done

SQLCMDPASSWORD="$MSSQL_SA_PASSWORD" /opt/mssql-tools18/bin/sqlcmd \
  -S sqlserver -U sa -C -b -h -1 -I -i /init/local-db-init.sql
