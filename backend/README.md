# Backend

A Java 25 / Spring Boot backend with HTTP sessions persisted in Redis and the
identity directory, audit trail and patients persisted in SQL Server 2022
(ADR 0013). Identities are provisioned by an external directory over a SCIM 2.0
interface (`/scim/v2`); the session-authenticated application API lives under
`/api`. Spring Session keeps session state in Redis, so it survives restarts
and is shared across instances.

## Prerequisites

- Java 25. Maven comes from the checked-in wrapper, `./mvnw`.
- Docker with Docker Compose (for the local SQL Server and Redis).
- An **x86_64** machine: Linux, WSL2 or an Intel Mac. The SQL Server image has
  no arm64 build; Apple Silicon is not a supported development platform
  (ADR 0013).
- At least 8 GB for WSL2 or Docker Desktop: the local SQL Server takes a 3 GB
  container, and the test suite starts one SQL Server (about 0.9 GB) per test
  JVM, two JVMs.

## Run locally

Copy `.env.example` to `.env`, then start SQL Server and Redis, which also
creates the local database and its three logins
([Local setup](docs/configuration/database.md#local-setup)):

```bash
make infra-up        # from the repository root
./mvnw spring-boot:run
```

Spring Boot does not read `.env` itself, so export it first (`make dev` from the
repository root does). It carries `DATABASE_URL` and the three database logins,
none of which has a default, and `SPRING_PROFILES_ACTIVE=local`, the profile
that runs the migrations at startup. Without `APP_DEV_FIXTURES_ENABLED=true` and
`APP_DEV_FIXTURES_PASSWORD`, startup refuses the shipped development role
mapping, because the Groups it names do not exist
([Role mapping](docs/configuration/role-mapping.md)).

The service listens on `http://localhost:8080`; its health endpoint is
`GET /actuator/health`.

## Log in

The identity that logs in is a **SCIM User** — there is no separate account
table. Startup seeds the **Bootstrap Admin** (`APP_BOOTSTRAP_USERNAME` /
`APP_BOOTSTRAP_PASSWORD`, development default `admin` / `P@ssw0rd`, never for
production) and the **Admin group**. With the development fixtures on, a freshly
seeded Bootstrap Admin owes no first-login password change; an existing one keeps
whatever flag it has. With the development fixtures on, there is
also a User per development Role, all with `APP_DEV_FIXTURES_PASSWORD`
([Role mapping](docs/configuration/role-mapping.md)). Lockout (the threshold is
`APP_LOCKOUT_MAX_ATTEMPTS`), session windows and their defaults are in
`/docs/domain/authentication.md` and `/docs/domain/sessions.md`.

Every unsafe request needs the session's CSRF token in the `X-CSRF-TOKEN`
header. It is fetched, never read from a cookie: `GET /api/auth/csrf` returns it
in the body (opening a session when there is none), and Login discards the
pre-login token, so fetch again once signed in:

```bash
csrf() { curl -s -c cookies.txt -b cookies.txt http://localhost:8080/api/auth/csrf | jq -r .token; }

curl -c cookies.txt -b cookies.txt \
  -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -H "X-CSRF-TOKEN: $(csrf)" \
  -d '{"username":"admin","password":"P@ssw0rd"}'

token=$(csrf)
curl -b cookies.txt http://localhost:8080/api/auth/me
curl -b cookies.txt http://localhost:8080/api/admin/accounts
curl -b cookies.txt -X POST -H "X-CSRF-TOKEN: $token" \
  http://localhost:8080/api/admin/accounts/<id>/unlock
```

`docs/openapi.yaml` documents every operation, status, body and required
Permission, and the baseline gate holds it against the running code
(`docs/api-contract-check.md`).

## Configuration

Export the variables `.env.example` lists, or load it as a dotenv file. Each
area's settings are in `docs/configuration/`, which
[`index.md`](docs/configuration/index.md) lists. Set
`SESSION_COOKIE_SECURE=true` when serving over HTTPS, and keep real credentials
in the deployment's secret manager.

## Bundle a frontend

The `with-frontend` Maven profile copies the built SPA into
`target/classes/static`; it is off by default, so `./mvnw clean verify` here
packages no SPA. The contract is in the root `AGENTS.md`, "Frontend/backend
integration":

```bash
make package     # from the repository root — builds the SPA first
./mvnw -Pwith-frontend -Dfrontend.dist.dir=/abs/path/to/frontend/dist package
```

`.br`/`.gz` siblings in the SPA build are copied verbatim (resource filtering is
off). The backend forwards client-side routes such as `/login` to `index.html`;
`/api`, `/actuator` and `/scim` stay backend-only (`/docs/domain/request-paths.md`).

`make container` from the repository root builds the deployable image, with the
SPA in it.

## Build and test

`./scripts/verify.sh` is the baseline gate: the build, the tests, the ArchUnit
rules and the Semgrep scan. It needs Docker for the Testcontainers-backed tests,
Semgrep on `PATH` (`pip install semgrep`), and network access for the Semgrep
registry packs. `AGENTS.md` is the authority on the gates.

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
starts no Spring context and no container, and its run fails if the log shows
either. A test that must reach the database as the driver sees it, without a
container, runs over `ScriptedJdbc`, a JDBC fake that fails on any statement its
script does not name, and so stays fast. Subsystem names are the script's
`SUBSYSTEM_NAMES`; a test package missing from it, and the root-package
classes, run only in the broad gate.

Live layers, run by hand and never by a gate: `npm run test:e2e` in
`frontend/`, and `make integration-test` from the repository root.
