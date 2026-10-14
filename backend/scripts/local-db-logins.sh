#!/usr/bin/env bash
# Creates the app and retention database logins in the local PostgreSQL that
# compose.yaml runs, each with the password backend/.env gives it. Re-runnable:
# it creates a login that is missing and resets each one's password, so a
# changed .env is applied by running it again. `make infra-up` and the E2E runs
# call it once Postgres is healthy; with plain `docker compose up`, run it
# yourself before the first start.
#
# Locally the owner login is the compose superuser (`backend`), which already
# owns the database, so nothing here creates it. The two NOLOGIN roles come from
# the V1 migration, and the V3 migration makes each login a member of its role,
# from the names the service passes Flyway — so this script grants nothing.
# Deployments create all three logins with infra/db-bootstrap.sh instead.
#
# Reads DATABASE_USERNAME / DATABASE_PASSWORD (the app login) and
# DATABASE_RETENTION_USERNAME / DATABASE_RETENTION_PASSWORD from the
# environment, or from backend/.env when DATABASE_USERNAME is unset. The
# passwords reach psql through its environment only (\getenv), never a
# command-line argument; the names are checked against the pattern the
# migrations enforce before they are put into SQL.
#
# Runs from anywhere; it cd's to the app root itself.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

die() {
  printf 'local-db-logins: %s\n' "$*" >&2
  exit 1
}

if [[ -z ${DATABASE_USERNAME:-} && -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source ./.env
  set +a
fi

for variable in DATABASE_USERNAME DATABASE_PASSWORD DATABASE_RETENTION_USERNAME DATABASE_RETENTION_PASSWORD; do
  [[ -n ${!variable:-} ]] || die "$variable is not set; copy it from .env.example into .env"
done
for login in "$DATABASE_USERNAME" "$DATABASE_RETENTION_USERNAME"; do
  [[ $login =~ ^[a-z][a-z0-9_]{2,30}$ ]] || die "a login name must match ^[a-z][a-z0-9_]{2,30}\$"
done
[[ $DATABASE_USERNAME != "$DATABASE_RETENTION_USERNAME" ]] ||
  die "DATABASE_USERNAME and DATABASE_RETENTION_USERNAME must name different logins"

# One idempotent session: create each login that is missing, then set its
# password from the psql variable \getenv read, quoted as a literal by psql.
sql() {
  local login
  for login in "$DATABASE_USERNAME" "$DATABASE_RETENTION_USERNAME"; do
    printf "SELECT format('CREATE ROLE %%I LOGIN', '%s') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%s')\n\\gexec\n" \
      "$login" "$login"
  done
  printf '%s\n' \
    '\getenv app_password APP_PASSWORD' \
    '\getenv retention_password RETENTION_PASSWORD'
  printf 'ALTER ROLE "%s" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'"'"'%s'"'"';\n' \
    "$DATABASE_USERNAME" app_password \
    "$DATABASE_RETENTION_USERNAME" retention_password
}

sql | APP_PASSWORD="$DATABASE_PASSWORD" RETENTION_PASSWORD="$DATABASE_RETENTION_PASSWORD" \
  docker compose -f compose.yaml exec -T -e APP_PASSWORD -e RETENTION_PASSWORD postgres \
  psql -X -q -v ON_ERROR_STOP=1 -U backend -d backend >/dev/null

printf 'local-db-logins: %s and %s are ready\n' "$DATABASE_USERNAME" "$DATABASE_RETENTION_USERNAME"
