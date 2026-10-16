#!/usr/bin/env bash
# The one-shot init of the local SQL Server: runs inside compose.yaml's
# `sqlserver-init` service once `sqlserver` is healthy, and creates the database
# with its creation options and the three logins from backend/.env
# (local-db-init.sql), resetting the password of a login whose .env password
# no longer signs in, then checks that each login signs in. Idempotent, so every
# `make infra-up` runs it again and a changed .env is applied; a run that finds
# everything in place changes nothing.
#
# It follows the deployed bootstrap (infra/infrastructure.yaml,
# /opt/backend/db-bootstrap.sh) step for step, with the local server's sa in
# place of RDS's master user and a self-signed certificate in place of RDS's.
#
# No secret is ever echoed or put on a command line: there is no `set -x`, sqlcmd
# runs without `-e`, each password reaches sqlcmd as SQLCMDPASSWORD, and every
# other value as a scripting variable sqlcmd reads from the environment. The
# values are checked here before sqlcmd runs, so the script never puts a
# malformed name or a quote into T-SQL.
set -euo pipefail

SQLCMD=/opt/mssql-tools18/bin/sqlcmd

die() {
  printf 'local-db-init: %s\n' "$*" >&2
  exit 1
}

for variable in MSSQL_SA_PASSWORD DATABASE_NAME DATABASE_OWNER_USERNAME DATABASE_OWNER_PASSWORD \
  DATABASE_USERNAME DATABASE_PASSWORD DATABASE_RETENTION_USERNAME DATABASE_RETENTION_PASSWORD; do
  [[ -n ${!variable:-} ]] || die "$variable is not set; copy it from .env.example into backend/.env"
done
[[ $DATABASE_NAME =~ ^[a-z][a-z0-9_]{0,63}$ ]] || die "DATABASE_NAME must match ^[a-z][a-z0-9_]{0,63}\$"
logins=("$DATABASE_OWNER_USERNAME" "$DATABASE_USERNAME" "$DATABASE_RETENTION_USERNAME")
passwords=("$DATABASE_OWNER_PASSWORD" "$DATABASE_PASSWORD" "$DATABASE_RETENTION_PASSWORD")
for login in "${logins[@]}"; do
  [[ $login =~ ^[a-z][a-z0-9_]{2,30}$ ]] || die "a login name must match ^[a-z][a-z0-9_]{2,30}\$"
done
[[ $DATABASE_OWNER_USERNAME != "$DATABASE_USERNAME" && $DATABASE_OWNER_USERNAME != "$DATABASE_RETENTION_USERNAME" &&
  $DATABASE_USERNAME != "$DATABASE_RETENTION_USERNAME" ]] ||
  die "the owner, app and retention logins must be three different logins"
for password in "${passwords[@]}"; do
  [[ $password != *"'"* && $password != *'$('* ]] || die "a database password must hold no quote and no \$("
done

# The local server's certificate is self-signed, so local sqlcmd trusts it
# (-C); the deployed bootstrap validates RDS's instead (-N, never -C).
as_sa() {
  SQLCMDPASSWORD="$MSSQL_SA_PASSWORD" "$SQLCMD" -S sqlserver -U sa -C -b -h -1 -I -l 15 "$@"
}

# signs_in <login> <password>: the login signs in with the password.
signs_in() {
  SQLCMDPASSWORD="$2" "$SQLCMD" -S sqlserver -U "$1" -C -b -h -1 -l 15 \
    -Q 'SET NOCOUNT ON; SELECT 1' >/dev/null 2>&1
}

# The logins whose password does not sign in, `|`-separated, for the SQL to
# reset (a missing login is among them, and the SQL creates it instead).
reset_logins="|"
for i in "${!logins[@]}"; do
  signs_in "${logins[i]}" "${passwords[i]}" || reset_logins+="${logins[i]}|"
done

RESET_LOGINS="$reset_logins" as_sa -i /init/local-db-init.sql

for i in "${!logins[@]}"; do
  signs_in "${logins[i]}" "${passwords[i]}" || die "login ${logins[i]} cannot sign in with its .env password"
done
printf 'db-init: database %s and its three logins are ready\n' "$DATABASE_NAME"
