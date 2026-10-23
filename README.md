# smart-on-fhir

Monorepo holding the frontend SPA and the backend service.

## Layout

`frontend/` (Vite + React + TypeScript SPA) and `backend/` (Spring Boot 4
service) are the two apps; `AGENTS.md` maps the rest of the tree and holds the
repo-wide rules. Each app is self-contained — its own `README.md`, `AGENTS.md`,
dependency manifest and tooling — so start there for anything app-specific.

Anything spanning both apps goes through the root `Makefile`. `make` on its own
lists every target; `make bootstrap`, `make dev`, `make verify`, and
`make package` are the ones you will reach for.

## Toolchain pins

Every toolchain version is pinned in the repo, so every machine resolves the same one:

| Tool  | Pinned in                                                       | Enforced by                                                                 |
| ----- | --------------------------------------------------------------- | --------------------------------------------------------------------------- |
| Node  | `/.nvmrc`, `/.tool-versions`, `frontend/package.json` `engines` | `frontend/.npmrc` (`engine-strict`), `scripts/lib.sh`                       |
| npm   | `frontend/package.json` `packageManager`                        | Corepack, plus `engines.npm`                                                |
| Maven | `backend/.mvn/wrapper/maven-wrapper.properties`                 | `backend/mvnw` (checksum-verified download), Enforcer `requireMavenVersion` |
| JDK   | `/.tool-versions`                                               | Enforcer `requireJavaVersion` in `backend/pom.xml` (`[25,26)`)              |

A bump to Node, Maven, or the JDK lands in every row of its line in the same
commit — a pin that disagrees with its neighbour surfaces as a build error
somewhere unrelated.

Activate the Node and JDK pins with `nvm use` (reads `/.nvmrc`), or
`asdf install` / `mise install` (both read `/.tool-versions`) from the repo
root. Maven needs nothing installed: build through `./mvnw`.

Container images in `backend/compose.yaml` and `backend/Dockerfile` are pinned
to an exact patch **and** a digest: the tag documents what the image is, the
digest is what gets pulled. Move both together:

```bash
docker buildx imagetools inspect <image>:<tag> --format '{{println .Manifest.Digest}}'
```

The SQL Server image is pinned twice — `backend/compose.yaml` and
`ContainerTestConfiguration.SQL_SERVER_IMAGE` — and the two must agree (the
backend's gate scripts read their shared test server's from `compose.yaml`); it is
reviewed monthly (ADR 0013, "Database patching"). It is x86_64 only: Apple
Silicon is not a supported development platform
(`backend/docs/configuration/database.md`, "Local setup"). The local Epic
launcher, `smartonfhir/smart-launcher-2`, publishes `latest` only, so its
digest alone is the pin.

## Releasing and deploying

`make package` builds the SPA and packages it into the Spring Boot JAR — the
release path; a plain `./mvnw clean verify` in `backend/` packages no SPA. The
build contract is in `AGENTS.md`, "Frontend/backend integration"; the runtime
contract (CSRF, CSP, sessions) is `frontend/docs/BACKEND_CONTRACT.md`.

`infra/` deploys that JAR to AWS with CloudFormation (`infra/README.md`). Two
requirements no template enforces:

- **Edge throttling.** The application has no request-rate limiter, and the
  stack provisions no AWS WAF web ACL: attach one that throttles `/scim/v2/**`
  per connector token and `POST /api/auth/login` / `POST
/api/auth/change-password` per session before exposing the service
  (`infra/docs/edge-throttling.md`).
- **No published defaults.** The stack sets every application variable on the
  instance; never let a deployment fall back to `application.yaml`'s defaults,
  which are published, not credentials.
