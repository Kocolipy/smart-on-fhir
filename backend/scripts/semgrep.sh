#!/usr/bin/env bash
# Static-analysis gate for the backend app. Scans backend/ only — the frontend
# owns its own scan via `npm run test:security`, with its own rules and its own
# ignore file. Nothing scans the monorepo as a whole.
#
# Two halves, deliberately, with the local half's rule tests run ahead of both:
#
#   semgrep/rules   Local rules, checked in. Offline, deterministic, and each
#                   rule carries the reason THIS service cares about it.
#   semgrep/tests   Rule-test fixtures for the local rules (`semgrep --test`),
#                   run first: a rule that stopped reporting what its fixture
#                   plants fails the gate before the scan runs. The scan skips
#                   them (.semgrepignore), since their planted lines are findings.
#   p/* packs       Registry rulesets for the generic Java and OWASP ground.
#                   The LIST below is fixed, but the packs' contents are not:
#                   they are resolved from the registry at run time and track
#                   upstream, so a pack gaining a rule can turn this gate red
#                   without a commit here. That is the trade for breadth. A
#                   network-less run (`--config semgrep/rules` alone) still
#                   gets the local half.
#
# Exits non-zero on any finding, so green means zero findings.
set -euo pipefail

LOCAL_RULES=semgrep/rules
LOCAL_RULE_TESTS=semgrep/tests

REGISTRY_RULESETS=(
  p/java
  p/security-audit
  p/owasp-top-ten
)

if ! command -v semgrep >/dev/null 2>&1; then
  echo "semgrep not found on PATH. Install it with: pip install semgrep" >&2
  exit 127
fi

cd "$(dirname "$0")/.."

# Each fixture in semgrep/tests is named after the rule file it tests, and marks every line a
# rule must report (`ruleid:`) and must leave alone (`ok:`). Non-zero on any miss or extra.
semgrep scan --test --config "${LOCAL_RULES}" "${LOCAL_RULE_TESTS}"

# `--project-root .` pins Semgrep's project root to backend/. Semgrep otherwise
# resolves it from git — the monorepo root — and then `.semgrepignore` here no
# longer replaces the built-in default skip list, which silently drops all of
# `src/test/java` from the scan. See the comments in `.semgrepignore`.
config_args=(--config "${LOCAL_RULES}")
for ruleset in "${REGISTRY_RULESETS[@]}"; do
  config_args+=(--config "${ruleset}")
done

exec semgrep scan "${config_args[@]}" --project-root . --error "$@" .
