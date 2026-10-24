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
IMAGE ?= smart-on-fhir
TAG ?= local

export NPM
export MVN

.PHONY: help bootstrap infra-up infra-down infra-logs epic-launcher-up epic-launcher-down fhircast-up fhircast-down \
        dev dev-stop verify-frontend verify-backend verify-contract verify integration-test package \
        container clean

help: ## Show the available targets
	@grep -hE '^[a-z][a-z-]*:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "} {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

bootstrap: ## Install frontend dependencies and prepare backend tooling
	@scripts/bootstrap.sh

infra-up: ## Start SQL Server and Redis, wait until both are healthy, and create the local database and its three logins from backend/.env
	$(COMPOSE) up -d --wait
	$(COMPOSE) run --rm sqlserver-init

infra-down: ## Stop SQL Server and Redis (volumes are kept)
	$(COMPOSE) down

infra-logs: ## Tail the dependency logs
	$(COMPOSE) logs -f

# Opt-in: the launcher sits behind its own compose profile, so `infra-up` above
# never starts it (`integration-test` starts its own).
# backend/docs/configuration/epic-login.md, "Local Epic launcher", has the
# APP_EPIC_* values that point the backend at it.
epic-launcher-up: ## Start the local SMART launcher (stand-in for Epic) with the dependencies
	$(COMPOSE) --profile epic-launcher up -d --wait
	$(COMPOSE) run --rm sqlserver-init

epic-launcher-down: ## Stop the local SMART launcher and the dependencies (volumes are kept)
	$(COMPOSE) --profile epic-launcher down

# Opt-in as well, behind the `fhircast` profile: Medplum as the SMART EHR
# launcher and FHIRcast hub, fronted by the DCR shim, on 127.0.0.1:8103 (and its
# sign-in page on 127.0.0.1:3103). The script seeds Medplum and prints the
# APP_EPIC_* values; backend/docs/configuration/epic-login.md, "Local FHIRcast
# stand-in", has the rest.
fhircast-up: ## Start the local FHIRcast stand-in (Medplum and the DCR shim) with the dependencies, and seed Medplum
	@scripts/fhircast-up.sh

fhircast-down: ## Stop the local FHIRcast stand-in and the dependencies (volumes are kept)
	$(COMPOSE) --profile fhircast down

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

# The one check that belongs to neither app: it reads both, which neither app
# may do (AGENTS.md, "Paths"), so it lives in scripts/ and runs after both
# app gates, once each side is known sound on its own.
verify-contract: ## SPA–API contract check: hold the SPA's mirrored vocabulary to backend/docs/openapi.yaml (scripts/verify-contract.sh)
	@scripts/verify-contract.sh

verify: verify-frontend verify-backend verify-contract ## Run both app gates, then the SPA–API contract check, serially

integration-test: ## Test the FHIRcast shim, package the JAR, start it twice with the dependencies, the SMART launcher and the FHIRcast stand-in, then run every Playwright project
	@scripts/integration-test.sh

package: ## Build the SPA and package it into the Spring Boot JAR
	@scripts/package.sh

container: package ## Build the deployable integrated image
	cd backend && docker build -t $(IMAGE):$(TAG) .
	@echo "built $(IMAGE):$(TAG) — run with: docker run --rm -p 8080:8080 --env-file backend/.env $(IMAGE):$(TAG)"

clean: ## Remove build output from both apps
	cd backend && $(MVN) -q -B clean
	rm -rf frontend/dist frontend/coverage frontend/playwright-report frontend/test-results
