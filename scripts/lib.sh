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
# handing off to spring-boot:run. Without it the app falls back to the
# application.yaml defaults, which include published default credentials.
load_backend_env() {
  local env_file="$BACKEND_DIR/.env"
  if [[ ! -f $env_file ]]; then
    warn "no backend/.env — using application.yaml defaults (including published default credentials)"
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

# Refuse to start when the port is taken. Without this the caller's readiness
# probe happily passes against a *previous* run's leftover process, and you debug
# a server you are not actually running.
require_port_free() {
  local port=$1 name=$2 info
  info="$(port_holder "$port")"
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
# The lifecycle `make integration-test` and `make epic-integration-test` share:
# compose dependencies, the backend under spring-boot:run, then Playwright,
# which starts the Vite dev server itself. A caller runs `stack_up`, exports
# whatever its backend and specs need, then `stack_e2e`. Everything started is
# torn down on exit unless KEEP_UP=1.

STACK_COMPOSE=()
stack_backend_pid=""

stack_cleanup() {
  local status=$?
  trap - INT TERM EXIT
  if [[ ${KEEP_UP:-0} == 1 ]]; then
    log "KEEP_UP=1 — leaving the backend and the compose services running"
  else
    log "tearing down"
    stop_group "$stack_backend_pid"
    "${STACK_COMPOSE[@]}" down >/dev/null 2>&1 || true
  fi
  exit "$status"
}

# stack_up <description> [compose profile]
#
# Checks the toolchain, starts the compose services (with the profile, when
# given), exports backend/.env and sets BACKEND_PORT. Refuses to run against a
# leftover backend or dev server: the suite would pass or fail against code that
# is not the tree under test.
stack_up() {
  local description=$1 profile=${2:-}
  require_node
  require_maven
  require_cmd curl
  require_docker
  [[ -d $FRONTEND_DIR/node_modules ]] ||
    die "frontend/node_modules missing — run 'make bootstrap' first."

  STACK_COMPOSE=(docker compose -f "$BACKEND_DIR/compose.yaml")
  [[ -z $profile ]] || STACK_COMPOSE+=(--profile "$profile")
  trap stack_cleanup INT TERM EXIT

  log "starting $description"
  "${STACK_COMPOSE[@]}" up -d --wait

  load_backend_env
  BACKEND_PORT="${SERVER_PORT:-8080}"
  require_port_free "$BACKEND_PORT" "the backend"
  require_port_free 5173 "the Vite dev server Playwright starts"
}

# stack_e2e <npm script>
#
# Starts the backend with the environment exported so far, waits for it, then
# runs the frontend's Playwright script.
stack_e2e() {
  local script=$1
  bg_start stack_backend_pid backend "$BACKEND_DIR" "$MVN" -q -DskipTests spring-boot:run
  wait_for_http "http://localhost:$BACKEND_PORT/actuator/health" backend 180 "$stack_backend_pid"

  log "running 'npm run $script' (Playwright starts the Vite dev server itself)"
  (cd "$FRONTEND_DIR" && "$NPM" run "$script")
}
