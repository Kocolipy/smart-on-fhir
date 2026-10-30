#!/usr/bin/env bash
# Run a long gate detached, then read back a short summary of its result.
#
#   scripts/gate.sh run  <log> [-C <dir>] <command...>
#   scripts/gate.sh read <log> [--wait <seconds>] [--max <lines>]
#
# `run` launches <command> from <dir> (default: the caller's directory) in its
# own session, writes all output to <log>, appends `GATE_EXIT=<status>` when it
# ends, and returns at once. A shell's foreground window or lossy output stream
# can therefore never lose the result.
#
# `read` waits for that sentinel, then prints only what decides the result:
# the sentinel, the test totals, the Spring context count, any scan findings
# line and, on a red gate, the failure lines (each truncated, the whole set
# capped). The log on disk stays the full record; open it only when the
# summary is not enough.
#
# `read --wait N` gives up after N seconds with exit 124 and "still running";
# call `read` again, never `run`, which would start a second gate writing to
# the same log.
#
# Exit status of `read` is the gate's own.
set -euo pipefail

usage() {
  sed -n '4,5p' "$0" | sed 's/^# //' >&2
  exit 2
}

# Lines longer than this are cut: Spring and Maven emit lines tens of KB long.
WIDTH=300

cmd_run() {
  local log=$1
  shift
  local dir=$PWD
  if [[ ${1:-} == -C ]]; then
    dir=$2
    shift 2
  fi
  [[ $# -gt 0 ]] || usage
  [[ -d $dir ]] || {
    echo "gate: no such directory: $dir" >&2
    exit 2
  }
  if [[ -f $log ]] && ! grep -q '^GATE_EXIT=' "$log"; then
    echo "gate: $log has no GATE_EXIT yet; a gate may still be writing it. Read it, or remove it first." >&2
    exit 2
  fi
  : >"$log"
  # $@ is passed through as positional arguments, never re-parsed as a string.
  (cd "$dir" && setsid nohup bash -c '"$@" >>"$0" 2>&1; echo "GATE_EXIT=$?" >>"$0"' "$log" "$@" \
    </dev/null >/dev/null 2>&1 &)
  echo "gate: started in $dir; log $log. Next: scripts/gate.sh read $log"
}

cmd_read() {
  local log=$1
  shift
  local wait=0 max=60
  while [[ $# -gt 0 ]]; do
    case $1 in
      --wait) wait=$2; shift 2 ;;
      --max) max=$2; shift 2 ;;
      *) usage ;;
    esac
  done
  [[ -f $log ]] || {
    echo "gate: no log at $log" >&2
    exit 2
  }

  local waited=0
  until grep -q '^GATE_EXIT=' "$log"; do
    if ((wait > 0 && waited >= wait)); then
      echo "gate: still running after ${waited}s ($(wc -l <"$log") log lines). Read again; do not relaunch."
      exit 124
    fi
    sleep 5
    waited=$((waited + 5))
  done

  local sentinel status
  sentinel=$(grep '^GATE_EXIT=' "$log" | tail -1)
  status=${sentinel#GATE_EXIT=}

  echo "$sentinel"
  # Maven's aggregate totals (the per-class lines carry "Time elapsed"), Vitest's
  # "Tests  N passed", and a scanner's findings count ("Findings: 0",
  # "0 findings"), never prose that merely mentions a finding.
  grep -E '^\[(INFO|WARNING|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|^ *Tests +[0-9]+|[Ff]indings?: [0-9]+|[0-9]+ findings?\b' "$log" |
    cut -c1-"$WIDTH" | tail -8 || true
  local contexts
  contexts=$(grep -c 'Started .* in .* seconds' "$log" || true)
  ((contexts == 0)) || echo "Spring contexts started: $contexts"
  grep -E '^\[INFO\] BUILD (SUCCESS|FAILURE)' "$log" | tail -1 || true

  if [[ $status != 0 ]]; then
    echo "--- failures (from $log) ---"
    # Maven's Results block names every failing test with its message; the rest
    # covers compiler, Enforcer, tsc, ESLint, Vitest, Prettier and shell errors.
    # The Maven "-> [Help 1]" boilerplate after a failure carries no information.
    local failures
    failures=$(grep -E '^\[ERROR\]|<<< (FAILURE|ERROR)!|FAIL |✗|×|error TS[0-9]+|^ +[0-9]+:[0-9]+ +error|AssertionError|^\[warn\]|Error:|error:' "$log" |
      grep -vE '^\[ERROR\] *$|\[Help 1\]|^\[ERROR\] (To see the full|Re-run Maven|For more information|-> )' |
      cut -c1-"$WIDTH" | awk '!seen[$0]++' || true)
    if [[ -n $failures ]]; then
      local count
      count=$(printf '%s\n' "$failures" | wc -l)
      printf '%s\n' "$failures" | head -n "$max"
      ((count <= max)) || echo "... $((count - max)) more failure lines; raise --max or read the log"
    else
      # Nothing matched a known shape: the end of the log is the best evidence.
      echo "(no recognised failure lines; last 30 lines follow)"
      grep -v '^GATE_EXIT=' "$log" | tail -30 | cut -c1-"$WIDTH"
    fi
  fi
  exit "$status"
}

[[ $# -ge 2 ]] || usage
sub=$1
shift
case $sub in
  run) cmd_run "$@" ;;
  read) cmd_read "$@" ;;
  *) usage ;;
esac
