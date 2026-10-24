#!/usr/bin/env bash
# Shared helpers for the root scripts/ layer. Source this, do not execute it.
#
# Every script here is invoked from the root Makefile, but each is also safe to
# run directly.
#
# The toolchain guards read the repo's own pin files rather than hardcoding
# versions, so a bump to /.nvmrc or /.tool-versions does not need an edit here.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_DIR="$ROOT_DIR/backend"

NPM="${NPM:-npm}"
# The checked-in wrapper, never a bare `mvn`: it downloads and checksum-verifies
# exactly the Maven release pinned in backend/.mvn/wrapper/maven-wrapper.properties.
MVN="${MVN:-$BACKEND_DIR/mvnw}"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarn:\033[0m %s\n' "$*" >&2; }
die() {
  printf '\033[1;31merror:\033[0m %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH.${2:+ $2}"
}

# Read a tool's pin out of /.tool-versions, e.g. pinned_version maven -> 3.9.11.
pinned_version() {
  awk -v tool="$1" '$1 == tool { print $2; exit }' "$ROOT_DIR/.tool-versions"
}

# Compare dotted versions: 0 when $1 >= $2.
#
# No `| head -1` on the sort: head closing the pipe early can SIGPIPE sort, and
# `set -o pipefail` would then report that as a comparison failure.
version_ge() {
  local sorted
  sorted="$(printf '%s\n%s\n' "$2" "$1" | sort -V)"
  [[ ${sorted%%$'\n'*} == "$2" ]]
}

# Node is the pin that actually bites: Vite 7 and Vitest 4 fail on an old major
# in ways that read as test failures rather than as a wrong Node. frontend/.npmrc
# sets engine-strict so npm refuses outright, but the dev server, Vitest and
# Playwright are invoked directly — hence this check.
require_node() {
  require_cmd node "Install the pinned Node: 'nvm use' or 'mise install' from the repo root."
  local pin actual
  pin="$(tr -d '[:space:]' <"$ROOT_DIR/.nvmrc")"
  actual="$(node -p 'process.versions.node')"
  if [[ ${actual%%.*} != "${pin%%.*}" ]] || ! version_ge "$actual" "$pin"; then
    die "Node $actual does not satisfy the pin $pin (/.nvmrc, frontend/package.json engines.node).
       Activate it from the repo root: 'nvm use', 'asdf install', or 'mise install'."
  fi
  require_cmd "$NPM"
  local npm_pin npm_actual
  npm_pin="$(node -p 'require("'"$FRONTEND_DIR"'/package.json").engines.npm.replace(/[^0-9.]/g, "")')"
  npm_actual="$("$NPM" --version)"
  version_ge "$npm_actual" "$npm_pin" ||
    die "npm $npm_actual is older than the pinned $npm_pin (frontend/package.json engines.npm).
       'corepack enable' activates the packageManager pin."
}

# Maven itself needs nothing installed — but the wrapper only launches Maven, so
# a JDK matching the enforcer's requireJavaVersion range must be on PATH.
require_maven() {
  [[ -x $MVN ]] || die "Maven wrapper not found or not executable at $MVN"
  require_cmd java "Install the pinned JDK: $(pinned_version java) (/.tool-versions)."
  local want actual
  # temurin-25.0.4.1+1 -> 25
  want="$(pinned_version java)"
  want="${want##*-}"
  want="${want%%.*}"
  actual="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
  if [[ -n $actual && -n $want && $actual != "$want" ]]; then
    die "Java $actual is on PATH but this build needs JDK $want (/.tool-versions, and the
       requireJavaVersion enforcer rule in backend/pom.xml). Set JAVA_HOME to the pinned JDK."
  fi
}

require_docker() {
  require_cmd docker "Install Docker; on WSL also enable the distro's Docker integration."
  docker compose version >/dev/null 2>&1 ||
    die "'docker compose' is unavailable. Is the Docker daemon running?"
}

# Spring Boot does not read dotenv files itself, so export backend/.env before
# handing off to spring-boot:run. Without it the database logins, which have no
# defaults, are unset and the backend refuses to start; every other setting falls
# back to the application.yaml defaults, which include published credentials.
load_backend_env() {
  local env_file="$BACKEND_DIR/.env"
  if [[ ! -f $env_file ]]; then
    warn "no backend/.env — the database logins are unset (copy backend/.env.example)"
    return 0
  fi
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
  log "loaded backend/.env"
}

# Wait until something answers on an HTTP URL. Any HTTP status counts as up:
# /actuator/health may legitimately answer 401 behind Spring Security.
#
# Takes the supervised child's pid so a process that died during startup is
# reported as a crash immediately, instead of as a timeout minutes later.
wait_for_http() {
  local url=$1 name=${2:-$1} timeout=${3:-120} pid=${4:-} waited=0
  require_cmd curl
  log "waiting for $name at $url (timeout ${timeout}s)"
  while ((waited < timeout)); do
    if curl -sS -o /dev/null --max-time 2 "$url" 2>/dev/null; then
      log "$name is up"
      return 0
    fi
    if [[ -n $pid ]] && ! kill -0 "$pid" 2>/dev/null; then
      die "$name exited before it started serving — see the [$name] output above."
    fi
    sleep 2
    waited=$((waited + 2))
    # Say something periodically: a silent multi-minute wait is indistinguishable
    # from a hang, which is exactly how this script earned "always gets stuck".
    ((waited % 20)) || log "still waiting for $name (${waited}s/${timeout}s)"
  done
  die "$name did not come up within ${timeout}s"
}

# Report what is listening on a TCP port, empty when free. Best effort: -p names
# the pid for our own processes, but a port published by Docker Desktop from the
# Windows side shows no pid under WSL — callers must handle that.
port_holder() {
  ss -ltnpH "sport = :$1" 2>/dev/null || true
}

# Succeed when $1:$2 accepts a TCP connection within 2 s. Use this, not
# port_holder, to ask "is a dependency up?": it tests exactly what the backend
# will do, so it holds under every WSL setup. Under mirrored networking or Docker
# Desktop a published port has no listening socket in WSL's own namespace (`ss`
# reports it free while connections succeed); under NAT a dependency on the
# Windows side is reached at the host's IP, not 127.0.0.1. Host and port are
# passed as arguments, never spliced into the inner script.
port_reachable() {
  timeout 2 bash -c 'exec 3<>"/dev/tcp/$0/$1"' "$1" "$2" 2>/dev/null
}

# Print "host port" for a jdbc:sqlserver:// URL, defaulting to localhost and 1433
# the way the driver does. Handles [IPv6] literals and drops a named instance and
# the ;-separated properties (the database name among them).
jdbc_host_port() {
  local rest=${1#jdbc:sqlserver://} host port
  rest=${rest%%;*}
  rest=${rest%%\\*}
  if [[ $rest == \[* ]]; then
    host=${rest%%]*}
    host=${host#[}
    port=${rest#*]}
  else
    host=${rest%%:*}
    port=${rest#"$host"}
  fi
  port=${port#:}
  printf '%s %s\n' "${host:-localhost}" "${port:-1433}"
}

# Refuse to start when the port is taken. Without this the caller's readiness
# probe happily passes against a *previous* run's leftover process, and you debug
# a server you are not actually running. `ss` names a holder inside WSL; the
# connect probe catches one it cannot see — a Windows process under mirrored
# networking, or a port Docker Desktop publishes.
require_port_free() {
  local port=$1 name=$2 info
  info="$(port_holder "$port")"
  if [[ -z $info ]] && port_reachable 127.0.0.1 "$port"; then
    info="something answers on localhost:$port but holds no socket inside WSL —
a Windows-side process or a Docker-published port"
  fi
  [[ -n $info ]] || return 0
  die "port $port is already in use, so $name cannot bind it:
       ${info//$'\n'/$'\n'       }
     A previous run may have left one behind — 'make dev-stop' clears them."
}

# Start a supervised background child and store its pid in the variable named by
# $1 (a nameref, NOT stdout).
#
# This must not be called through command substitution: `pid=$(...)` runs in a
# subshell, so the process would be that subshell's child and the pid useless to
# the caller — `wait` rejects it and a group kill misses it, which is how an
# earlier version orphaned backends that kept holding port 8080.
#
# `set -m` puts the child in its own process group, which is what makes a whole
# subtree (mvnw -> java, npm -> vite) killable without signalling this script.
bg_start() {
  local -n _bg_pid=$1
  local label=$2 dir=$3
  shift 3
  local cmd
  cmd="$(printf '%q ' "$@")"
  set -m
  # stdin from /dev/null: a background process group that reads the terminal is
  # stopped by SIGTTIN, which froze Vite (its keyboard shortcuts read stdin) the
  # first time a key was pressed in the `make dev` terminal.
  bash -c "cd ${dir@Q} && ${cmd} 2>&1 | sed -u 's|^|[${label}] |'" </dev/null &
  _bg_pid=$!
  set +m
}

# Stop a process group started by bg_start: TERM, then KILL what ignores it.
stop_group() {
  local pid=${1:-} grace=${2:-10} waited=0
  [[ -n $pid ]] || return 0
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || return 0
  while kill -0 -- "-$pid" 2>/dev/null && ((waited < grace)); do
    sleep 1
    waited=$((waited + 1))
  done
  kill -KILL -- "-$pid" 2>/dev/null || true
}

# --- Full-stack E2E runs ----------------------------------------------------
#
# The lifecycle `make integration-test` runs: compose dependencies, the
# backends, then Playwright, which starts the Vite dev server itself. A caller
# runs `stack_up`, exports whatever its backends and specs need, starts each
# backend with `stack_backend`, then runs `stack_e2e`. Everything started is
# torn down on exit unless KEEP_UP=1.

STACK_COMPOSE=()
STACK_BACKEND_PIDS=()

stack_cleanup() {
  local status=$?
  trap - INT TERM EXIT
  if [[ ${KEEP_UP:-0} == 1 ]]; then
    log "KEEP_UP=1 — leaving the backends and the compose services running"
  else
    log "tearing down"
    local pid
    for pid in "${STACK_BACKEND_PIDS[@]}"; do stop_group "$pid"; done
    "${STACK_COMPOSE[@]}" down >/dev/null 2>&1 || true
  fi
  exit "$status"
}

# stack_up <description> [compose profile...]
#
# Checks the toolchain, starts the compose services (with each profile given),
# exports backend/.env and sets BACKEND_PORT. Refuses to run against a leftover
# backend or dev server: the suite would pass or fail against code that is not
# the tree under test.
stack_up() {
  local description=$1 profile
  shift
  require_node
  require_maven
  require_cmd curl
  require_docker
  [[ -d $FRONTEND_DIR/node_modules ]] ||
    die "frontend/node_modules missing — run 'make bootstrap' first."

  STACK_COMPOSE=(docker compose -f "$BACKEND_DIR/compose.yaml")
  for profile in "$@"; do STACK_COMPOSE+=(--profile "$profile"); done
  trap stack_cleanup INT TERM EXIT

  log "starting $description"
  "${STACK_COMPOSE[@]}" up -d --wait
  # The database and the three logins the backend connects as (backend/docs/configuration/database.md,
  # "Local setup"), by the one-shot init, in the foreground so a failure stops the
  # run; idempotent, so a volume that has them already is fine.
  "${STACK_COMPOSE[@]}" run --rm sqlserver-init

  load_backend_env
  BACKEND_PORT="${SERVER_PORT:-8080}"
  require_port_free "$BACKEND_PORT" "the backend"
  require_port_free 5173 "the Vite dev server Playwright starts"
}

# stack_backend <label> <port> <command...>
#
# Starts a backend with the command given and the environment exported so far
# (an `env NAME=value ...` prefix sets what differs for this one), and waits
# until it answers on the port. One at a time: each runs the `local` profile's
# migrations and seed at startup, against the one database they share.
stack_backend() {
  local label=$1 port=$2 pid
  shift 2
  bg_start pid "$label" "$BACKEND_DIR" "$@"
  STACK_BACKEND_PIDS+=("$pid")
  wait_for_http "http://localhost:$port/actuator/health" "$label" 180 "$pid"
}

# stack_e2e <npm script>
#
# Runs the frontend's Playwright script against the backends started so far.
stack_e2e() {
  local script=$1
  log "running 'npm run $script' (Playwright starts the Vite dev server itself)"
  (cd "$FRONTEND_DIR" && "$NPM" run "$script")
}

# --- The local Epic stand-in ------------------------------------------------
#
# Medplum and the DCR shim, the compose `fhircast` profile
# (backend/docs/configuration/epic-login.md, "Local Epic stand-in").

# fhircast_seed <compose array name>
#
# Seeds Medplum through the one-shot `medplum-init`, in the foreground so a
# failure stops the caller, and sets FHIRCAST_CLIENT_ID from what it prints.
# The seed's first run on a fresh
# volume replaces Medplum's signing key, which Medplum reads at startup alone,
# so that run restarts Medplum, and the shim, whose Medplum token the old key
# signed, and seeds once more.
fhircast_seed() {
  local -n _compose=$1
  local seeded
  seeded="$("${_compose[@]}" run --rm -T medplum-init)" ||
    die "seeding Medplum failed — see the medplum-init output above."
  if [[ $seeded == *MEDPLUM_RESTART_REQUIRED=true* ]]; then
    log "Medplum's signing key was replaced: restarting Medplum and the shim to load it"
    "${_compose[@]}" restart medplum fhircast-shim
    "${_compose[@]}" up -d --wait
    seeded="$("${_compose[@]}" run --rm -T medplum-init)" ||
      die "seeding Medplum failed — see the medplum-init output above."
  fi
  FHIRCAST_CLIENT_ID="$(sed -n 's/^MEDPLUM_CLIENT_ID=//p' <<<"$seeded")"
  [[ -n $FHIRCAST_CLIENT_ID ]] || die "the Medplum seed printed no client id"
}

# compose_env <compose array name> <service> <variable>
#
# Prints one environment value of a compose service as compose resolves it, so
# a caller reads the stand-in's settings from compose.yaml, their one home,
# rather than repeating them. The `init` profile is added so that the
# one-shots' settings resolve too.
compose_env() {
  local -n _compose_cmd=$1
  "${_compose_cmd[@]}" --profile init config --format json |
    node -e '
      let text = "";
      process.stdin.on("data", (chunk) => (text += chunk));
      process.stdin.on("end", () => {
        const env = JSON.parse(text).services[process.argv[1]]?.environment ?? {};
        const value = env[process.argv[2]];
        if (value === undefined || value === null) process.exit(1);
        process.stdout.write(String(value));
      });
    ' "$2" "$3" || die "compose.yaml sets no $3 on $2"
}
