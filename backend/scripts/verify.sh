#!/usr/bin/env bash
# Baseline gate for the backend app — the one command that decides whether a
# backend change is complete. Green means the build, the tests, the ArchUnit
# rules (which run inside `verify`) and the Semgrep scan are all clean.
#
# This script is the single source of truth for what the backend baseline IS.
# The root Makefile's `verify-backend` target calls it rather than listing the
# sub-gates itself, so the Makefile and backend/AGENTS.md cannot drift apart.
#
# Conditional gates stay out of here on purpose: PIT mutation testing is scoped
# to the tests you touched, so it carries its own trigger and its own completion
# criterion. See backend/AGENTS.md.
#
# Runs from anywhere; it cd's to the app root itself.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# The checked-in wrapper, never a bare `mvn`: it downloads and checksum-verifies
# the pinned Maven release, and the build's Enforcer rejects a wrong JDK. `make`
# exports MVN as an absolute path to that same wrapper.
MVN=${MVN:-./mvnw}

# One SQL Server for every test JVM, removed on exit (scripts/test-sql-server.sh).
. ./scripts/test-sql-server.sh
start_test_sql_server

"$MVN" -B clean verify
./scripts/semgrep.sh
