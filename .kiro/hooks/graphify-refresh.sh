#!/usr/bin/env bash
# Kiro `stop` hook: refresh the knowledge graph at the end of a turn in which
# code or docs actually changed.
#
# Why `stop` and not a commit hook: AGENTS.md wants the graph refreshed in the
# same change that caused it, and agents mostly do not commit -- the human does.
# `stop` fires at the one boundary every agent reliably hits: the end of the
# turn, just before it reports done.
#
# Cost: `graphify update` is AST-only, no LLM, no API spend. This script runs it
# as a plain subprocess -- no model tokens are consumed. The graphify-runner
# agent (.kiro/agents/graphify-runner.json) is for the cases that need judgment,
# which this script refuses to guess at (see the failure path at the bottom).
#
# Fails open: any missing prerequisite exits 0 so a turn is never held hostage
# to graph tooling.

set -uo pipefail

root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
out="$root/graphify-out"
graph="$out/graph.json"
interp_file="$out/.graphify_python"
log="$out/.graphify_refresh.log"
lock="$out/.graphify_refresh.lock"

[ -r "$interp_file" ] || exit 0
[ -r "$graph" ] || exit 0
interp="$(cat "$interp_file")"
[ -x "$interp" ] || exit 0

# --- Is a refresh even warranted? -------------------------------------------
# Cheap staleness test: any tracked-or-untracked source file newer than
# graph.json. Without this the hook would pay a subprocess on every Q&A turn.
mapfile -t changed < <(
  cd "$root" || exit 0
  git status --porcelain --untracked-files=all 2>/dev/null |
    sed 's/^...//' |
    grep -aEv '^(graphify-out/|\.kiro/|\.claude/|\.codex/)' |
    grep -aE '\.(java|ts|tsx|js|jsx|py|sh|md|ya?ml|json|xml|sql|Dockerfile)$' || true
)
[ "${#changed[@]}" -gt 0 ] || exit 0

newer=0
for f in "${changed[@]}"; do
  [ -f "$root/$f" ] || continue
  if [ "$root/$f" -nt "$graph" ]; then
    newer=1
    break
  fi
done
[ "$newer" -eq 1 ] || exit 0

# Testing escape: report what WOULD be refreshed and stop.
if [ -n "${GRAPHIFY_REFRESH_DRY_RUN:-}" ]; then
  printf 'would refresh; trigger files: %s\n' "${changed[*]}" >&2
  exit 0
fi

# --- One refresh at a time --------------------------------------------------
# `stop` fires per turn; overlapping `update` runs would race graph.json.
exec 9>"$lock"
flock -n 9 || exit 0

# --- Refresh ----------------------------------------------------------------
{
  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) graphify update . (stop hook)"
  printf 'trigger files: %s\n' "${changed[*]}"
} >"$log"

if timeout 600 "$interp" -m graphify update . >>"$log" 2>&1; then
  exit 0
fi

status=$?
{
  echo "REFRESH_EXIT=$status"
} >>"$log"

# Nonzero exit routes STDERR to the user as a warning. Do NOT retry with
# --force here: a shrinking rebuild is graphify's shrink guard doing its job, and
# overriding it unattended can silently drop nodes. That is the judgment call
# the graphify-runner agent exists for.
cat >&2 <<EOF
graphify refresh failed (exit $status) -- the knowledge graph is now stale.
Log: graphify-out/.graphify_refresh.log
Dispatch the graphify-runner agent to diagnose it:
  kiro-cli chat --agent graphify-runner --no-interactive "refresh the graph; the stop hook failed"
EOF
exit 1
