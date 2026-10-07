# monorepo-base

Monorepo holding the frontend SPA and the backend service.

## Layout

`frontend/` (Vite + React + TypeScript SPA) and `backend/` (Spring Boot 4
service) are the two apps; `AGENTS.md` maps the rest of the tree and holds the
repo-wide rules.

Each app is self-contained: its own `README.md`, `AGENTS.md`,
dependency manifest, and test/quality tooling. Start there for anything
app-specific — this file only covers the repo as a whole.

Anything spanning both apps goes through the root `Makefile`. `make` on its own
prints every target with a description, so that list lives there rather than
here; `make bootstrap`, `make dev`, `make verify`, and `make package` are the
ones you will reach for.

## Toolchain pins

Every toolchain version is pinned in the repo, so a developer's machine and CI
resolve the same one:

| Tool  | Pinned in                                                       | Enforced by                                                                 |
| ----- | --------------------------------------------------------------- | --------------------------------------------------------------------------- |
| Node  | `/.nvmrc`, `/.tool-versions`, `frontend/package.json` `engines` | `frontend/.npmrc` (`engine-strict`), `scripts/lib.sh`                       |
| npm   | `frontend/package.json` `packageManager`                        | Corepack, plus `engines.npm`                                                |
| Maven | `backend/.mvn/wrapper/maven-wrapper.properties`                 | `backend/mvnw` (checksum-verified download), Enforcer `requireMavenVersion` |
| JDK   | `/.tool-versions`                                               | Enforcer `requireJavaVersion` in `backend/pom.xml` (`[25,26)`)              |

Activate the Node and JDK pins with whichever manager you use — `nvm use` (reads
`/.nvmrc`), or `asdf install` / `mise install` (both read `/.tool-versions`) from
the repo root. Maven needs nothing installed: build through `./mvnw`.

Container images are pinned to an exact patch **and** a digest, in
`backend/compose.yaml` and `backend/Dockerfile`. The tag documents what the
image is; the digest is what actually gets pulled. To move one, bump the tag and
re-resolve the digest together:

```bash
docker buildx imagetools inspect postgres:18.6-alpine --format '{{println .Manifest.Digest}}'
```

The one exception is the local Epic launcher, `smartonfhir/smart-launcher-2`:
upstream publishes `latest` only, so its digest alone is the pin.

A bump to Node, Maven, or the JDK has to land in every row of the table above in
the same commit — a pin that disagrees with its neighbour is worse than no pin,
because the failure surfaces as a build error somewhere unrelated.

## Working on the frontend

```bash
cd frontend
npm ci               # always ci, never install — see AGENTS.md
npm run dev          # Vite dev server
npm test             # vitest
npm run test:e2e     # Playwright
npm run lint         # eslint
npm run typecheck    # tsc -b
```

Full script list is in `frontend/package.json`.

## Working on the backend

```bash
cd backend
./mvnw spring-boot:run   # run the service
./scripts/verify.sh      # the baseline gate: build, tests, ArchUnit, Semgrep
docker compose up        # Postgres + Redis dependencies
```

Copy `backend/.env.example` to `.env` before running. Requires the pinned JDK
on `PATH` (see the table above); Maven itself comes from `./mvnw`.

Logs are ECS JSON on stdout; set `LOG_FILE` to also write a rolling JSON file
(deployed at `/var/log/backend/backend.json`). See `backend/README.md` under
"Logging" and `infra/README.md` under "View Logs".

## Frontend/backend integration

```bash
make package     # build the SPA, then package it into the Spring Boot JAR
```

That is the release path; a plain `./mvnw clean verify` in `backend/` packages
no SPA. The build contract is in `AGENTS.md` under "Frontend/backend
integration", and the runtime contract (CSRF, CSP, sessions) in
`frontend/AGENTS.md` under "Backend contract".

## Deploying to AWS

```bash
cd infra
./get-vpc-info.sh vpc-YOUR_VPC_ID   # inspect an existing VPC
./deploy.sh                          # create the stack, optionally ship the JAR
./cleanup.sh                         # delete the stack
```

`infra/` holds the CloudFormation template (`infrastructure.yaml`) and its
scripts; it is not an app, and `make infra-up` (local Postgres and Redis) has
nothing to do with it. Run the scripts from `infra/`: `deploy.sh` finds the
template, the key and the repo from its own location, but writes
`<stack>-outputs.txt` to the current directory, which is where `cleanup.sh`
deletes it from. `deploy.sh` builds the shippable JAR by calling
`scripts/package.sh` — the integrated path, so what reaches the instance has the
SPA in it. The stack provisions ALB + EC2 + RDS PostgreSQL + ElastiCache Redis
in `ap-southeast-1`; details, parameters, and troubleshooting are in
`infra/README.md`.

**Edge throttling is a deployment requirement.** The application has no
request-rate limiter. The edge must throttle `/scim/v2/**` per connector token,
`POST /api/auth/login`, and `POST /api/auth/change-password` per session.
Per-account throttling is the deterrent that matters. Per-source (per-IP) limits
are left to the edge's own policy, because Users behind a shared proxy or NAT
present one address. The stack provisions no AWS WAF web ACL, so attach one before
exposing the service. Scope, keys and rationale are in `infra/README.md`'s "Edge
throttling" section.

The local deploy artefacts in `infra/` — a `parameters.json` copied from the
template, `deploy.sh`'s `*-outputs.txt`, and the `*.pem` key you save — are
gitignored. Set the application's env vars explicitly on the instance: the
backend's `application.yaml` fallbacks are published defaults, not credentials.
