# Root orchestration for the monorepo. Per-app detail stays in frontend/ and
# backend/; this file only composes their existing entry points. Each target
# carries its description after '##'; `make` (or `make help`) lists them.
#
# Toolchain versions are pinned in /.nvmrc, /.tool-versions,
# frontend/package.json and backend/.mvn/wrapper/maven-wrapper.properties;
# scripts/lib.sh checks the active Node/npm/JDK against them before doing work.
# Activate them from the repo root with 'nvm use' / 'mise install'.

SHELL := /usr/bin/env bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help
# Every gate here shells out to a tool that already parallelises internally, and
# `verify` is specified as serial — so never let -j interleave targets.
.NOTPARALLEL:

NPM ?= npm
# The checked-in wrapper, not whatever `mvn` happens to be on PATH: it downloads
# and checksum-verifies exactly the Maven release pinned in
# backend/.mvn/wrapper/maven-wrapper.properties. Absolute, because it is both
# run from backend/ here and exported to scripts/ that cd elsewhere.
MVN ?= $(CURDIR)/backend/mvnw
COMPOSE ?= docker compose -f backend/compose.yaml
IMAGE ?= monorepo-base
TAG ?= local

export NPM
export MVN

.PHONY: help bootstrap infra-up infra-down infra-logs epic-launcher-up epic-launcher-down \
        dev dev-stop verify-frontend verify-backend verify integration-test epic-integration-test \
        package container clean

help: ## Show the available targets
	@grep -hE '^[a-z][a-z-]*:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "} {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

bootstrap: ## Install frontend dependencies and prepare backend tooling
	@scripts/bootstrap.sh

infra-up: ## Start PostgreSQL and Redis, waiting until both are healthy
	$(COMPOSE) up -d --wait

infra-down: ## Stop PostgreSQL and Redis (volumes are kept)
	$(COMPOSE) down

infra-logs: ## Tail the dependency logs
	$(COMPOSE) logs -f

# Opt-in: the launcher sits behind its own compose profile, so `infra-up` above
# never starts it. backend/README.md, "Local Epic launcher", has the APP_EPIC_*
# values that point the backend at it.
epic-launcher-up: ## Start the local SMART launcher (stand-in for Epic) with the dependencies
	$(COMPOSE) --profile epic-launcher up -d --wait

epic-launcher-down: ## Stop the local SMART launcher and the dependencies (volumes are kept)
	$(COMPOSE) --profile epic-launcher down

dev: ## Run backend and frontend together (Ctrl-C stops both)
	@scripts/dev.sh

dev-stop: ## Kill leftover backend/Vite processes from an earlier dev run
	@scripts/dev-stop.sh

# Each app owns its own baseline gate; these targets only invoke it, so the
# sub-gate list lives in one place per app and cannot drift from the app's docs.
# Serial on purpose: each app's failure should be the thing that stops the run.
verify-frontend: ## Frontend gate: frontend/ baseline (npm run verify)
	cd frontend && $(NPM) run verify

verify-backend: ## Backend gate: backend/ baseline (scripts/verify.sh)
	cd backend && ./scripts/verify.sh

verify: verify-frontend verify-backend ## Run both app gates, serially

integration-test: ## Start dependencies + both apps, then run Playwright
	@scripts/integration-test.sh

epic-integration-test: ## Epic Login E2E gate: launcher + dependencies + both apps, then the Epic spec
	@scripts/epic-integration-test.sh

package: ## Build the SPA and package it into the Spring Boot JAR
	@scripts/package.sh

container: package ## Build the deployable integrated image
	cd backend && docker build -t $(IMAGE):$(TAG) .
	@echo "built $(IMAGE):$(TAG) — run with: docker run --rm -p 8080:8080 --env-file backend/.env $(IMAGE):$(TAG)"

clean: ## Remove build output from both apps
	cd backend && $(MVN) -q -B clean
	rm -rf frontend/dist frontend/coverage frontend/playwright-report frontend/test-results
