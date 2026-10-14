# shellcheck shell=bash
# Shared by deploy.sh, db-bootstrap.sh and smoke.sh. Sourced, never run.
#
# Secret discipline (ADR 0015, "The bootstrap does not echo"; IM8 as-8). Every
# script in this directory:
#   - runs without command tracing: `set -x` prints every expanded secret;
#   - keeps a secret value in a shell variable only. It reaches a process on a
#     pipe, through a process substitution (a /dev/fd path) or in the
#     environment of that one process, never as a command-line argument (which
#     `ps` and /proc show every local user) and never in a file;
#   - names a secret in its messages, never prints its value.
# `printf` is a bash builtin, so `printf '%s' "$secret" | cmd` puts the value on
# cmd's stdin without it appearing in any argv.

set +x

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_info() { printf '%b[INFO]%b %s\n' "$GREEN" "$NC" "$*"; }
print_warn() { printf '%b[WARN]%b %s\n' "$YELLOW" "$NC" "$*"; }
print_error() { printf '%b[ERROR]%b %s\n' "$RED" "$NC" "$*" >&2; }
die() {
  print_error "$*"
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "$1 is required${2:+: $2}"
}

# --- Stack outputs -----------------------------------------------------------

STACK_OUTPUTS=""

# load_stack_outputs <stack> <region>
load_stack_outputs() {
  STACK_OUTPUTS=$(aws cloudformation describe-stacks --stack-name "$1" --region "$2" \
    --query 'Stacks[0].Outputs' --output json) || die "cannot read the outputs of stack $1"
}

# stack_output <key>: one output of the stack load_stack_outputs read.
stack_output() {
  printf '%s' "$STACK_OUTPUTS" |
    jq -er --arg k "$1" '.[] | select(.OutputKey == $k) | .OutputValue' ||
    die "stack output $1 is missing; was the stack created from this template?"
}

# --- Secrets -----------------------------------------------------------------

# secret_string <secret-arn> <region> [version-stage]: the SecretString, on stdout.
secret_string() {
  aws secretsmanager get-secret-value --secret-id "$1" --region "$2" \
    ${3:+--version-stage "$3"} --query SecretString --output text
}

# json_field <json> <field>: a string field of a secret's JSON, failing when absent.
json_field() {
  printf '%s' "$1" | jq -er --arg f "$2" '.[$f] | strings'
}

# require_login_name <what> <value>: the name pattern infrastructure.yaml enforces,
# checked again because the name is placed in DDL (ADR 0015, as-2).
require_login_name() {
  [[ $2 =~ ^[a-z][a-z0-9_]{2,30}$ ]] || die "$1 must match ^[a-z][a-z0-9_]{2,30}\$"
}

# --- PostgreSQL over TLS -----------------------------------------------------
#
# DB_HOST is the name the server certificate is checked against (the RDS
# endpoint), DB_HOSTADDR where the connection actually goes (the tunnel's local
# end), DB_PORT that address's port, DB_NAME the database and DB_CA_FILE the CA
# bundle. TLS runs end to end, psql to the server, inside the tunnel.

# fetch_rds_ca_bundle <region> <file>
fetch_rds_ca_bundle() {
  curl -fsS --proto '=https' -o "$2" \
    "https://truststore.pki.rds.amazonaws.com/$1/$1-bundle.pem" ||
    die "cannot download the RDS CA bundle for $1"
}

# open_stack_db_tunnel <region>: from the outputs load_stack_outputs read,
# downloads the RDS CA bundle and opens an SSH tunnel from a local port to the
# database through the instance (RDS is in private subnets), then sets the DB_*
# variables psql_as reads. The tunnel carries psql's TLS session unopened, so no
# credential is ever sent to the instance. The SSH key is KEY_FILE, or
# <KeyPairName>.pem beside these scripts. close_stack_db_tunnel ends it.
INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_WORK=""
TUNNEL_CTL=""
TUNNEL_TARGET=""
open_stack_db_tunnel() {
  local region=$1 key_file db_port
  key_file=${KEY_FILE:-$INFRA_DIR/$(stack_output KeyPairName).pem}
  [[ -r $key_file ]] ||
    die "SSH key $key_file not found: retrieve it (README, \"SSH Key Management\") or set KEY_FILE"
  TUNNEL_TARGET="ec2-user@$(stack_output EC2PublicIP)"
  DB_HOST=$(stack_output DBEndpoint)
  db_port=$(stack_output DBPort)
  DB_NAME=$(stack_output DBName)
  require_login_name "the DBName output" "$DB_NAME"
  DB_WORK=$(mktemp -d)
  DB_CA_FILE="$DB_WORK/rds-ca-bundle.pem"
  fetch_rds_ca_bundle "$region" "$DB_CA_FILE"
  DB_HOSTADDR=127.0.0.1
  DB_PORT=$((20000 + RANDOM % 20000))
  TUNNEL_CTL="$DB_WORK/ssh-control"
  ssh -i "$key_file" -o StrictHostKeyChecking=accept-new -o ExitOnForwardFailure=yes \
    -o BatchMode=yes -o ConnectTimeout=15 -f -N -M -S "$TUNNEL_CTL" \
    -L "127.0.0.1:$DB_PORT:$DB_HOST:$db_port" "$TUNNEL_TARGET" ||
    die "cannot open an SSH tunnel to the database through $TUNNEL_TARGET"
}

close_stack_db_tunnel() {
  if [[ -n $TUNNEL_CTL ]]; then
    ssh -S "$TUNNEL_CTL" -O exit "$TUNNEL_TARGET" >/dev/null 2>&1 || true
    TUNNEL_CTL=""
  fi
  if [[ -n $DB_WORK ]]; then
    rm -rf "$DB_WORK"
    DB_WORK=""
  fi
}

# psql_as <user> <password> [psql args...]: one session, verify-full TLS unless
# DB_SSLMODE says otherwise. The password is in that psql's environment only.
psql_as() {
  local user=$1 password=$2
  shift 2
  PGHOST="$DB_HOST" PGHOSTADDR="$DB_HOSTADDR" PGPORT="$DB_PORT" PGDATABASE="$DB_NAME" \
    PGUSER="$user" PGPASSWORD="$password" PGSSLMODE="${DB_SSLMODE:-verify-full}" \
    PGSSLROOTCERT="$DB_CA_FILE" PGCONNECT_TIMEOUT=15 PGAPPNAME=infra-deploy \
    psql -X -q -v ON_ERROR_STOP=1 "$@"
}

# db_bootstrap_sql: the SQL that creates the three logins and hands the database
# to the owner login, run as the RDS master user. Re-runnable: it creates what
# is missing and resets every login's password to its secret's.
#
# The owner login gets no CREATEROLE. Instead the two NOLOGIN roles V1 would
# create are created here first (V1 creates them only IF NOT EXISTS), and the
# owner holds them WITH ADMIN OPTION, which is all V1's
# `GRANT backend_app, backend_audit_retention TO CURRENT_USER` needs.
#
# The SQL text holds no password (ADR 0015, "The bootstrap does not echo";
# as-2): psql reads each one from its own environment into a psql variable
# (\getenv) and quotes it as a literal itself (:'var'). run_db_bootstrap
# supplies that environment.
#
# Reads DB_NAME, OWNER_LOGIN, APP_LOGIN and RETENTION_LOGIN, each checked by the
# caller with require_login_name, so safe to double-quote.
db_bootstrap_sql() {
  local login
  for login in "$OWNER_LOGIN" "$APP_LOGIN" "$RETENTION_LOGIN" backend_app backend_audit_retention; do
    printf "SELECT format('CREATE ROLE %%I NOLOGIN', '%s') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%s')\n\\gexec\n" \
      "$login" "$login"
  done
  printf '%s\n' \
    '\getenv owner_password OWNER_PASSWORD' \
    '\getenv app_password APP_PASSWORD' \
    '\getenv retention_password RETENTION_PASSWORD'
  # NOSUPERUSER, NOREPLICATION and NOBYPASSRLS are a new role's defaults, and
  # only a superuser may state them, which the RDS master user is not.
  printf 'ALTER ROLE "%s" WITH LOGIN NOCREATEDB NOCREATEROLE PASSWORD :'"'"'%s'"'"';\n' \
    "$OWNER_LOGIN" owner_password \
    "$APP_LOGIN" app_password \
    "$RETENTION_LOGIN" retention_password
  printf '%s\n' \
    '\unset owner_password' '\unset app_password' '\unset retention_password' \
    "GRANT backend_app, backend_audit_retention TO \"$OWNER_LOGIN\" WITH ADMIN OPTION;" \
    "GRANT backend_app TO \"$APP_LOGIN\";" \
    "GRANT backend_audit_retention TO \"$RETENTION_LOGIN\";" \
    "GRANT \"$OWNER_LOGIN\" TO CURRENT_USER;" \
    "ALTER DATABASE \"$DB_NAME\" OWNER TO \"$OWNER_LOGIN\";" \
    "REVOKE ALL ON DATABASE \"$DB_NAME\" FROM PUBLIC;" \
    "GRANT CONNECT ON DATABASE \"$DB_NAME\" TO \"$APP_LOGIN\", \"$RETENTION_LOGIN\";"
}

# run_db_bootstrap <master-user> <master-password>: db_bootstrap_sql through one
# psql session as the master user, with OWNER_PASSWORD, APP_PASSWORD and
# RETENTION_PASSWORD in that psql's environment only (\getenv needs psql 15+).
run_db_bootstrap() {
  [[ -n $OWNER_PASSWORD && -n $APP_PASSWORD && -n $RETENTION_PASSWORD ]] ||
    die "a database login's password is empty"
  db_bootstrap_sql |
    OWNER_PASSWORD="$OWNER_PASSWORD" APP_PASSWORD="$APP_PASSWORD" \
      RETENTION_PASSWORD="$RETENTION_PASSWORD" psql_as "$1" "$2"
}

# require_psql_getenv: the local psql is 15 or newer, the first with \getenv.
require_psql_getenv() {
  local version
  version=$(psql --version | grep -oE '[0-9]+' | head -n 1)
  ((${version:-0} >= 15)) || die "psql ${version:-?} is too old: db-bootstrap.sh needs psql 15 or newer (\\getenv)"
}

# check_tls_enforced <user> <password>: a plaintext session is refused for
# requiring TLS, and a verify-full session negotiates TLS 1.2 or higher.
check_tls_enforced() {
  local out version
  if out=$(DB_SSLMODE=disable psql_as "$1" "$2" -tAc 'SELECT 1' 2>&1); then
    die "a non-TLS connection to the database was ACCEPTED (expected rds.force_ssl = 1 to refuse it)"
  fi
  [[ $out == *pg_hba.conf* ]] ||
    die "the non-TLS connection failed, but not because TLS is required: $out"
  print_info "non-TLS connection refused by the server"

  version=$(psql_as "$1" "$2" -tAc 'SELECT version FROM pg_stat_ssl WHERE pid = pg_backend_pid()') ||
    die "the TLS connection (sslmode=verify-full) failed"
  [[ $version == TLSv1.2 || $version == TLSv1.3 ]] ||
    die "the TLS connection negotiated '$version', below TLS 1.2"
  print_info "TLS connection with a validated server certificate: $version"
}

# --- Post-deploy secret-leak check -------------------------------------------

# scan_for_secrets <source> <name>=<value>...: reads text on stdin and fails if
# any value occurs in it, naming the secret and never printing the value. The
# value reaches grep as a pattern file through a process substitution. Empty
# values are skipped (an empty pattern matches everything).
scan_for_secrets() {
  local source=$1 text pair name value leaked=0
  shift
  text=$(cat)
  [[ -n $text ]] || die "secret-leak check: $source is empty or could not be read"
  for pair in "$@"; do
    name=${pair%%=*}
    value=${pair#*=}
    [[ -n $value ]] || continue
    if printf '%s' "$text" | grep -qF -f <(printf '%s\n' "$value"); then
      print_error "secret-leak check: the value of $name appears in $source"
      leaked=1
    fi
  done
  ((leaked == 0)) || return 1
  print_info "secret-leak check: no secret value in $source"
}

# --- SCIM smoke ----------------------------------------------------------------
#
# scim_smoke <base-url>: signs in as the Bootstrap Admin (ADMIN_USERNAME,
# ADMIN_PASSWORD), mints a one-day token on a throwaway connector, and over
# SCIM creates a User, finds it in the list, deletes it and confirms it is gone;
# then deletes the connector, which revokes the token, and signs out.
#
# The first sign-in on a fresh stack must replace the seeded password. The new
# one comes from new_admin_password, and is handed to stage_admin_password
# BEFORE the change and to commit_admin_password after it, so a failure between
# the two leaves the new value recoverable. The caller defines all three; they
# run inside the smoke's subshell, whose own variables are all named smoke_*.
#
# Runs in a subshell, so its cleanup trap and working directory end with it.
# Request bodies holding a credential travel on curl's stdin, and the CSRF and
# bearer headers through process substitutions; responses are read into shell
# variables. The cookie jar is the one file, in a private temporary directory
# removed on exit, and its session is signed out at the end.
scim_smoke() (
  set -euo pipefail
  smoke_base=$1
  smoke_dir=$(mktemp -d)
  chmod 0700 "$smoke_dir"
  smoke_jar="$smoke_dir/cookies"
  smoke_connector=""
  smoke_user=""
  smoke_token=""
  smoke_csrf_header=""
  smoke_csrf_token=""

  # request <method> <path> [json-body]: sets STATUS and BODY. CSRF header from
  # the last csrf call; bearer token when one is held and the path is SCIM.
  request() {
    local method=$1 path=$2 out
    local -a args=(-sS -X "$method" -b "$smoke_jar" -c "$smoke_jar" -H 'Accept: application/json'
      -w '\n%{http_code}' --max-time 30)
    # The credential headers, read by curl from a /dev/fd path.
    credential_headers() {
      if [[ $path == /scim/* ]]; then
        printf 'Authorization: Bearer %s\n' "$smoke_token"
      elif [[ -n $smoke_csrf_token ]]; then
        printf '%s: %s\n' "$smoke_csrf_header" "$smoke_csrf_token"
      fi
    }
    if (($# > 2)); then
      args+=(-H 'Content-Type: application/json' --data-binary @-)
      out=$(printf '%s' "$3" | curl "${args[@]}" -H @<(credential_headers) "$smoke_base$path")
    else
      out=$(curl "${args[@]}" -H @<(credential_headers) "$smoke_base$path" </dev/null)
    fi
    STATUS=${out##*$'\n'}
    BODY=${out%$'\n'*}
    [[ $BODY != "$STATUS" ]] || BODY=""
  }
  expect() { # <status> <what>
    [[ $STATUS == "$1" ]] || die "SCIM smoke: $2 answered $STATUS, expected $1"
  }
  csrf() {
    request GET /api/auth/csrf
    expect 200 "GET /api/auth/csrf"
    smoke_csrf_header=$(printf '%s' "$BODY" | jq -er .headerName)
    smoke_csrf_token=$(printf '%s' "$BODY" | jq -er .token)
  }
  login() {
    local smoke_body
    smoke_body=$(printf '%s' "$ADMIN_PASSWORD" | jq -Rsc --arg u "$ADMIN_USERNAME" '{username: $u, password: .}')
    csrf
    request POST /api/auth/login "$smoke_body"
    expect 200 "Login as the Bootstrap Admin"
  }
  # shellcheck disable=SC2329 # invoked by the EXIT trap below
  cleanup() {
    local status=$?
    trap - EXIT
    if [[ -n $smoke_user && -n $smoke_token ]]; then request DELETE "/scim/v2/Users/$smoke_user" || true; fi
    if [[ -n $smoke_connector ]]; then
      { csrf && request DELETE "/api/admin/connectors/$smoke_connector"; } || true
    fi
    rm -rf "$smoke_dir"
    exit "$status"
  }
  trap cleanup EXIT

  login
  if [[ $(printf '%s' "$BODY" | jq -r .passwordChangeRequired) == true ]]; then
    print_info "SCIM smoke: replacing the Bootstrap Admin's seeded password"
    smoke_new=$(new_admin_password)
    stage_admin_password "$smoke_new"
    smoke_body=$(jq -nc --rawfile c <(printf '%s' "$ADMIN_PASSWORD") --rawfile n <(printf '%s' "$smoke_new") \
      '{currentPassword: $c, newPassword: $n}')
    csrf
    request POST /api/auth/change-password "$smoke_body"
    expect 204 "the Bootstrap Admin's password change"
    commit_admin_password "$smoke_new"
    ADMIN_PASSWORD=$smoke_new
    login
  fi

  smoke_stamp="deploy-smoke-$(date +%Y%m%d%H%M%S)-$RANDOM"
  csrf
  request POST /api/admin/connectors "$(jq -nc --arg n "$smoke_stamp" '{displayName: $n}')"
  expect 201 "creating the smoke connector"
  smoke_connector=$(printf '%s' "$BODY" | jq -er .id)
  csrf
  request POST "/api/admin/connectors/$smoke_connector/tokens" \
    '{"permissions":["user:read","user:write"],"lifetimeDays":1}'
  expect 201 "issuing the smoke connector's token"
  smoke_token=$(printf '%s' "$BODY" | jq -er .presentedValue)

  request POST /scim/v2/Users \
    "$(jq -nc --arg u "$smoke_stamp" '{schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"], userName: $u}')"
  expect 201 "SCIM create"
  smoke_user=$(printf '%s' "$BODY" | jq -er .id)
  print_info "SCIM smoke: created User $smoke_user"

  smoke_filter=$(jq -rn --arg u "$smoke_stamp" '"userName eq \"\($u)\"" | @uri')
  request GET "/scim/v2/Users?filter=$smoke_filter"
  expect 200 "SCIM list"
  [[ $(printf '%s' "$BODY" | jq -r --arg id "$smoke_user" '[.Resources[]? | select(.id == $id)] | length') == 1 ]] ||
    die "SCIM smoke: the created User is missing from the list"
  print_info "SCIM smoke: listed it"

  request DELETE "/scim/v2/Users/$smoke_user"
  expect 204 "SCIM delete"
  request GET "/scim/v2/Users/$smoke_user"
  expect 404 "reading the deleted User"
  smoke_user=""
  print_info "SCIM smoke: deleted it"

  csrf
  request DELETE "/api/admin/connectors/$smoke_connector"
  expect 204 "deleting the smoke connector"
  smoke_connector=""
  csrf
  request DELETE /api/auth/logout
  expect 204 "logout"
  print_info "SCIM smoke: create, list and delete passed"
)
