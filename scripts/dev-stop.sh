#!/usr/bin/env bash
# Kill leftover dev processes from an earlier `make dev` / `make integration-test`.
#
# Selection is deliberately NOT `pgrep -f <path>`: that matches any process whose
# command line merely MENTIONS the path — a grep, an editor, a shell echoing it —
# and kills it.
#
# Instead a process must prove it is ours twice over:
#   1. it either holds one of our ports, or its executable is java/node, and
#   2. its working directory is inside this checkout (/proc/<pid>/cwd).
# A shell that merely talks about the repo fails test 1; a java server in another
# project fails test 2.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

# True when the pid's working directory is inside this checkout.
pid_in_repo() {
  local cwd
  cwd="$(readlink -f "/proc/$1/cwd" 2>/dev/null || true)"
  [[ -n $cwd && ($cwd == "$ROOT_DIR" || $cwd == "$ROOT_DIR"/*) ]]
}

pid_cmd() { tr '\0' ' ' <"/proc/$1/cmdline" 2>/dev/null | cut -c1-70; }

killed=0

terminate() {
  local pid=$1 why=$2 pgid
  # Never signal this script, its parents, or the process group we live in.
  [[ $pid == "$$" || $pid == "$PPID" ]] && return 0
  pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')"
  [[ -n $pgid && $pgid != "$(ps -o pgid= -p $$ | tr -d ' ')" ]] || {
    warn "skipping pid $pid — it shares this shell's process group"
    return 0
  }
  log "stopping pid $pid ($why): $(pid_cmd "$pid")"
  if [[ $pgid == "$pid" ]]; then
    # Group leader: started by bg_start, so the group is exactly its subtree.
    kill -TERM -- "-$pid" 2>/dev/null || true
  else
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
  fi
  killed=1
}

# 1. Anything listening on our ports, when it belongs to this checkout: the
# backend's is the SERVER_PORT `make dev` started it on, from backend/.env.
[[ ! -f $BACKEND_DIR/.env ]] || load_backend_env
for spec in "$(backend_port):backend" "5173:frontend"; do
  port=${spec%%:*}
  name=${spec##*:}
  line="$(port_holder "$port")"
  [[ -n $line ]] || continue
  pids="$(grep -o 'pid=[0-9]*' <<<"$line" | cut -d= -f2 | sort -u || true)"
  if [[ -z $pids ]]; then
    warn "port $port ($name) is held by a process this shell cannot identify — likely Docker or another user. Not touching it."
    continue
  fi
  while read -r pid; do
    [[ -n $pid ]] || continue
    if pid_in_repo "$pid"; then
      terminate "$pid" "listening on $port"
    else
      warn "port $port is held by pid $pid, which is not running from this checkout — not touching it."
    fi
  done <<<"$pids"
done

# 2. A crashed-mid-startup server holds no port. Match on the EXECUTABLE being a
# JVM or node — a shell mentioning the repo can never match this — plus cwd.
while read -r pid; do
  [[ -n $pid ]] || continue
  pid_in_repo "$pid" || continue
  case "$(pid_cmd "$pid")" in
  *spring-boot* | *vite* | *maven*) terminate "$pid" "stray $(basename "$(readlink -f "/proc/$pid/exe" 2>/dev/null || echo unknown)")" ;;
  esac
done < <(pgrep -x 'java|node' 2>/dev/null || true)

if ((killed)); then
  sleep 2
  log "leftover dev processes stopped"
else
  log "no leftover dev processes found"
fi
