# Backend

A Java 25 / Spring Boot backend with HTTP sessions persisted in Redis and the
identity directory, audit trail and patients persisted in SQL Server 2022
(ADR 0013).
Identities are provisioned by an external directory over a SCIM 2.0 interface
(`/scim/v2`); the session-authenticated application API lives under `/api`.
Spring Session replaces the servlet container's in-memory session, so session
state can survive application restarts and be shared by multiple application
instances.

## Prerequisites

- Java 25
- Maven — not required; the checked-in wrapper (`./mvnw`) downloads and
  checksum-verifies the release pinned in `.mvn/wrapper/maven-wrapper.properties`
- Docker with Docker Compose (for the local SQL Server and Redis)
- An **x86_64** machine: Linux, WSL2 or an Intel Mac. The SQL Server image has no
  arm64 build. On Apple Silicon it runs only under Rosetta emulation (Docker
  Desktop's "Use Rosetta for x86_64/amd64 emulation"), which Microsoft does not
  support and which is slow; Apple Silicon is not a supported development
  platform (ADR 0013).
- Memory: the local SQL Server is capped at 2 GB (`MSSQL_MEMORY_LIMIT_MB`) in a
  3 GB container, and the test suite starts one SQL Server per test JVM — about
  0.9 GB each, two JVMs — so give WSL2 or Docker Desktop at least 8 GB.

## Run locally

Copy `.env.example` to `.env`, then start SQL Server and Redis, which also
creates the local database and its three logins (see
[Local setup](docs/configuration/database.md#local-setup)):

```bash
make infra-up        # from the repository root
# or, from backend/:
docker compose up -d --wait && docker compose run --rm sqlserver-init
```

Start the application:

```bash
./mvnw spring-boot:run
```

Spring Boot does not read `.env` itself, so export it first (`make dev` from the
repository root does). It carries `DATABASE_URL` and the three database logins,
none of which has a default, and `SPRING_PROFILES_ACTIVE=local`, the profile that runs the
migrations at startup. Without `APP_DEV_FIXTURES_ENABLED=true` and
`APP_DEV_FIXTURES_PASSWORD` from it, startup refuses the shipped development
role mapping, because the Groups it names do not exist; see
[Role mapping](docs/configuration/role-mapping.md).

The service listens on `http://localhost:8080`. Its health endpoint is
`GET /actuator/health`.

## Log in

Authentication is database-backed, and the identity that logs in is a **SCIM
User** — there is no separate account table. On startup the service idempotently
seeds the **Bootstrap Admin** and the server-reserved **Admin group** with the
Bootstrap Admin as its immutable member, creating whichever is absent. That is
the only User every deployment gets: `APP_BOOTSTRAP_USERNAME` /
`APP_BOOTSTRAP_PASSWORD` configure it, with the development default `admin` /
`P@ssw0rd`, which must not be used in production.

The non-administrative `user`, and one User per Role, are **development
fixtures** (`authorization.yaml`, `app.dev-fixtures`), seeded only when
`APP_DEV_FIXTURES_ENABLED=true`, all with the password in
`APP_DEV_FIXTURES_PASSWORD`. `.env.example` enables them.

There is no role column and no administrative role: every active User holds
baseline access (`ROLE_USER`), which is self-service only, and everything else is
granted by **Permission** through the role mapping, resolved when the session is
created and reported, sorted by name, as `permissions` on `GET /api/auth/me`.
With the development fixtures enabled there is a User per development Role to sign
in as, all with `APP_DEV_FIXTURES_PASSWORD` (the Superuser's is the Bootstrap
Admin), plus a Group-less `dormant` User the startup dormancy run locks. See
[Role mapping](docs/configuration/role-mapping.md).

Three consecutive refused logins lock a User (`APP_LOCKOUT_MAX_ATTEMPTS`, default
3, with no enforced floor), and the lock is **permanent**: there is no duration
setting, and only an Unlock by a holder of `user:write` ends it. Every refusal is
the same bare `401`. The Bootstrap Admin is the one User exempt. A session ends
after `SESSION_TIMEOUT` (default 15 minutes) without a request, or
`APP_SESSION_ABSOLUTE_LIFETIME` (default 8 hours) after it was created, whichever
comes first. The rules behind all of this are in `/docs/domain/authentication.md`
and `/docs/domain/sessions.md`.

Every unsafe request (POST, PUT, PATCH, DELETE) needs the session's CSRF token in
the `X-CSRF-TOKEN` header. It is fetched, never read from a cookie: `GET
/api/auth/csrf` returns it in the body (and opens a session when there is none).
Login discards the pre-login token, so fetch again once signed in. Log in and keep
the returned session cookie in a cookie jar:

```bash
csrf() { curl -s -c cookies.txt -b cookies.txt http://localhost:8080/api/auth/csrf | jq -r .token; }

curl -c cookies.txt -b cookies.txt \
  -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -H "X-CSRF-TOKEN: $(csrf)" \
  -d '{"username":"admin","password":"P@ssw0rd"}'

token=$(csrf)

curl -b cookies.txt http://localhost:8080/api/auth/me
```

All `/api` endpoints other than login and the CSRF token require that cookie, and
every one beyond self-service requires its own Permission — declared per
operation in `docs/openapi.yaml`'s `security` field; a session without it is
answered with `403`, as is any route the document does not declare. Keep sending
the cookie, and the token on unsafe requests.
For example, the Bootstrap Admin (a Superuser, holding every Permission) lists the
directory's Users (never with a password hash) and Unlocks one by its stable id:

```bash
curl -b cookies.txt http://localhost:8080/api/admin/accounts

curl -b cookies.txt -X POST -H "X-CSRF-TOKEN: $token" \
  http://localhost:8080/api/admin/accounts/<id>/unlock
```

The administration API only reads what the directory owns (`userName`,
`active`, Group membership); its writes are Unlock and the forced password
change. Deactivation is the directory's, over SCIM.

`docs/openapi.yaml` documents every operation, status and body — the
authentication, session, self-service (`/api/self`), patient, administration
(accounts, groups, connectors and their tokens, audit events) and SCIM
surfaces — and the baseline gate holds it against the running code (see
`docs/api-contract-check.md`).

## Configuration

Copy `.env.example` to `.env` if your runtime loads dotenv files, or export the
variables in your shell. The defaults connect to Redis at `localhost:6379` and
expire sessions after 15 minutes of inactivity — the same value every
environment uses, including deployed ones (`infra/infrastructure.yaml`), so the
SPA can rely on a single window. The database has no defaults at all: set
`DATABASE_URL` and the logins [Database logins](docs/configuration/database.md) lists, in
every environment. A start without `DATABASE_URL` fails, naming it.

Set `SESSION_COOKIE_SECURE=true` when serving the application over HTTPS. Store
real Redis credentials in your deployment's secret manager; do not commit them.
Each setting's reference is in `docs/configuration/`, one file per area
([`index.md`](docs/configuration/index.md) lists them):

- [Role mapping](docs/configuration/role-mapping.md) — Roles, their Groups, and the development fixtures.
- [Logging](docs/configuration/logging.md) — the ECS JSON records and the log file.
- [SCIM release gate](docs/configuration/scim.md) — `APP_SCIM_ENABLED`.
- [Epic Login](docs/configuration/epic-login.md) — the `APP_EPIC_*` variables and the local Epic launcher.
- [Audit trail](docs/configuration/audit.md) — retention, and the audit append pool.
- [Operational telemetry](docs/configuration/telemetry.md) — the metrics scrape and the management port.
- [Dormancy](docs/configuration/dormancy.md) — the two windows and the job.
- [Database logins](docs/configuration/database.md) — the three logins, migrations, and the local SQL Server.
- [Session storage](docs/configuration/sessions.md) — the indexed Redis repository.

## Bundle a frontend

The SPA is never committed here. It is copied straight from the frontend's build
output into `target/classes/static` when the executable JAR is built:

```text
frontend source -> frontend/dist -> backend/target/classes/static -> JAR
```

That copy is the `with-frontend` Maven profile, and it is **off by default**, so
`./mvnw clean verify` and `./mvnw package` here are pure backend builds requiring no
Node and packaging no SPA. Turn it on for a release:

```bash
# from the repository root — builds the SPA first, then packages
make package

# or directly, against an already-built SPA
./mvnw -Pwith-frontend -Dfrontend.dist.dir=/abs/path/to/frontend/dist package
```

`frontend.dist.dir` defaults to `../frontend/dist` (the sibling app in this
monorepo) and must contain `index.html` at its root, for example:

```text
dist/
├── index.html
└── assets/
    ├── app.js
    └── app.css
```

If `index.html` is not there, the profile's `validate`-phase enforcer fails the
build — it will not package a missing or half-built frontend. `.br`/`.gz`
siblings emitted by the build are copied verbatim (resource filtering is off, so
binaries are not corrupted).

The backend serves static files and forwards client-side routes such as
`/login` to `index.html`; `/api/**` and `/actuator/**` remain
backend-only paths.

## Build and test

```bash
./scripts/verify.sh
```

That is the baseline gate: the build, the tests, the ArchUnit rules
(`src/test/java/arch/ArchitectureTest.java`) and the Semgrep scan
(`./scripts/semgrep.sh`, which exits non-zero on any finding). It needs Docker
for the Testcontainers-backed tests and Semgrep on `PATH` (`pip install
semgrep`); the first scan downloads the registry packs, so it needs network
access. Mutation testing with PIT is a conditional gate on top of it.
`AGENTS.md` is the authority on what each gate covers, when PIT runs and with
which mutators, and when a surviving mutant may be accepted.

### Test layers

Faster checks for the edit loop. None replaces the baseline gate.

| Layer          | Command                                    | Needs                    |
| -------------- | ------------------------------------------ | ------------------------ |
| Fast           | `./scripts/test-gates.sh fast`             | JDK only                 |
| Subsystem      | `./scripts/test-gates.sh subsystem <name>` | Docker                   |
| All subsystems | `./scripts/test-gates.sh subsystems`       | Docker                   |
| Broad          | `./scripts/test-gates.sh broad`            | Docker                   |
| Baseline       | `./scripts/verify.sh`                      | Docker, Semgrep, network |

The fast gate is an explicit class list in the script: every test class that
starts no Spring context and no container. Its run fails if the log shows
either, so a class that grows a `@SpringBootTest` has to move out. Subsystem
names are the test packages (`arch`, `auth`, `scim`, `audit`, `authorization`,
`contract`, `observability`, `database`, `lifecycle`, `scheduling`, `web`);
`BackendApplicationTests`, at the package root, runs only in the broad gate.

The test JVMs drop every `.env.example` key from their environment
(`excludedEnvironmentVariables` in `pom.xml`), so the results do not depend on
what the shell exported.

A test that must reach the database as the driver sees it, without a container,
runs over `ScriptedJdbc`, a JDBC fake at the driver boundary that fails on any
statement its script does not name, and so stays in the fast gate.

Live layers, run by hand and never by a gate: `npm run test:e2e` in
`frontend/`, `make integration-test` and `make epic-integration-test` from the
repository root, and the Semgrep registry download inside `./scripts/semgrep.sh`.

Build the deployable container, with the SPA in it, from the repository root:

```bash
make container
```
