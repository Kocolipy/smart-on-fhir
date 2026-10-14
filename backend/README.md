# Backend

A Java 25 / Spring Boot backend with HTTP sessions persisted in Redis and the
identity directory, audit trail and per-User counters persisted in SQL Server 2022
(ADR 0015).
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
  platform (ADR 0015).
- Memory: the local SQL Server is capped at 2 GB (`MSSQL_MEMORY_LIMIT_MB`) in a
  3 GB container, and the test suite starts one SQL Server per test JVM — about
  0.9 GB each, two JVMs — so give WSL2 or Docker Desktop at least 8 GB.

## Run locally

Copy `.env.example` to `.env`, then start SQL Server and Redis, which also
creates the local database and its three logins (see
[Local setup](#local-setup)):

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
[Role mapping](#role-mapping).

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

There is no role column and no administrative role. Every active User holds
baseline access (`ROLE_USER`), which is self-service only; everything else is
granted by **Permission**. A session holds the Permissions the role mapping
confers through the User's direct Group memberships, resolved when the session is
created — so a User added to a mapped Group gains them at its next login, while a
User removed from one loses them at once, because the removal ends its sessions —
and reported, sorted by name, as
`permissions` on `GET /api/auth/me`. With the
development fixtures enabled there is a User per development Role to sign in
as, all with `APP_DEV_FIXTURES_PASSWORD` (the Superuser's is the Bootstrap
Admin), plus a Group-less `dormant` User the startup dormancy run locks. See
[Role mapping](#role-mapping).

Three consecutive refused logins lock an account (`APP_LOCKOUT_MAX_ATTEMPTS`,
default 3), and the lock is **permanent**: it has no duration, nothing lifts it as time passes, and an Unlock by
a holder of `user:write` is the only thing that ends it. Imposing it also revokes that account's
live sessions, so a locked account stops acting immediately rather than when the
session it already held expires. While the lockout holds the correct password is
refused too, and every refusal — unknown username, a credentialless account,
wrong password, locked account — answers with the same bare `401` after an
equivalent Argon2id verification, so the response cannot be used to find out
which accounts exist, which have a password set, or which are locked. An accepted
login resets the count. `APP_LOCKOUT_MAX_ATTEMPTS` configures the threshold, with
no enforced floor on the value; there is no duration setting to configure.

The Bootstrap Admin (`APP_BOOTSTRAP_USERNAME`) is the deployment's recovery
identity and is the one User exempt from lockout: its failed attempts are
counted and audited, but it never locks. Without that exemption a permanent
lockout would let an unauthenticated attacker brick the deployment by guessing at
the recovery account until it closed. The accepted cost is unbounded online
guessing against that one account, answered by the Argon2id verification cost
every attempt pays, the uniform refusal, and the audit trail — not by a lock.

A session is bound by two independent limits. It is dropped after
`SESSION_TIMEOUT` (default 15 minutes) of inactivity — the servlet container's
own idle timeout, reset by every request — and separately terminated once it has
existed for `APP_SESSION_ABSOLUTE_LIFETIME` (default 8 hours), regardless of how
recently it was used. Both bounds apply to every authenticated session, a
Superuser's included; whichever is reached first ends the session.

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
authentication, session, counter, self-service (`/api/self`), administration
(accounts, groups, connectors and their tokens, audit events) and SCIM
surfaces — and the baseline gate holds it against the running code (see
`docs/api-contract-check.md`).

## Configuration

Copy `.env.example` to `.env` if your runtime loads dotenv files, or export the
variables in your shell. The defaults connect to Redis at `localhost:6379` and
expire sessions after 15 minutes of inactivity — the same value every
environment uses, including deployed ones (`infra/infrastructure.yaml`), so the
SPA can rely on a single window. The database has no defaults at all: set
`DATABASE_URL` and the logins [Database logins](#database-logins) lists, in
every environment. A start without `DATABASE_URL` fails, naming it.

Set `SESSION_COOKIE_SECURE=true` when serving the application over HTTPS. Store
real Redis credentials in your deployment's secret manager; do not commit them.

### Role mapping

The `app.authorization` block defines the deployment's **Roles** — named sets of
Permissions — and maps each Role to a Group by the Group's stable id. A User's
Permissions are the union of the Roles of the mapped Groups it is a direct member
of, resolved at login. It is read-only configuration: no endpoint creates or
changes a Role or a mapping entry. The shipped default is a development mapping,
in `src/main/resources/authorization.yaml`:

```yaml
app:
  authorization:
    roles:
      - name: Account admin
        permissions: [user:read, user:write, group:read]
      # ...
    groups:
      - id: 00000000-0000-4000-8000-00000000a001 # a Group's SCIM stable id
        role: Superuser
        superuser: true # exactly one entry
      - id: 00000000-0000-4000-8000-00000000a002
        role: Account admin
```

The Permission names are a closed set defined in code, in
`authorization/domain/Permission.java`.

`counter:read` and `counter:write` are also **baseline Permissions**: every
active User holds them at sign-in whatever its Groups, so no Role needs to list
them for its members to use the counter. A session confined by a required
password change does not hold them.

Startup fails, naming every problem in one message, on: an unknown Permission; a Role defined
twice or without a name; an entry with no Group id, mapping a Group id twice, or
naming an undefined Role; anything but exactly one `superuser: true` entry; a
Superuser Role missing any Permission; and a mapped Group id that does not
resolve to a Group, or a Superuser Group that is not the reserved Admin group.
The Superuser Group IS the Admin group: seeding creates the Admin group under
that entry's id, so give the Superuser entry the id the Admin group should have —
or, on a database seeded earlier, the id it already has. Every other id must
belong to a Group that exists when the service starts, so provision those Groups
before deploying a mapping that names them.

**Replace the list whole.** `roles` and `groups` are lists, and a list from a
higher-precedence source replaces the shipped one entirely — for example a file
passed with `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/backend/authorization.yaml`,
or indexed environment variables (`APP_AUTHORIZATION_GROUPS_0_ID`,
`APP_AUTHORIZATION_GROUPS_0_ROLE`, `APP_AUTHORIZATION_GROUPS_0_SUPERUSER`, …).

**Development fixtures.** The shipped mapping names four Groups no deployment
has. With the fixtures enabled, startup seeds them under those ids, each with one
User, so local runs and the e2e suite have a User per Role; without them, a
deployment that did not replace the mapping fails startup instead of running with
it. They also seed two Users in no Group: `user`, holding only baseline access, and
`dormant`, whose dormancy basis is reset
past the lockout window at every startup (see [Dormancy](#dormancy)). Fixtures
never overwrite a User or Group that already exists.

| Variable                    | Default | Meaning                                                                  |
| --------------------------- | ------- | ------------------------------------------------------------------------ |
| `APP_DEV_FIXTURES_ENABLED`  | `false` | Seed the development Groups and their Users (`.env.example` sets `true`) |
| `APP_DEV_FIXTURES_PASSWORD` | none    | Every fixture User's password; required when enabled, with no fallback   |

Each development Role's Permissions are in `authorization.yaml`; the Superuser
holds every one.

| Role            | Fixture Group      | Fixture User        |
| --------------- | ------------------ | ------------------- |
| Superuser       | the Admin group    | the Bootstrap Admin |
| Account admin   | `Account admins`   | `account-admin`     |
| Auditor         | `Auditors`         | `auditor`           |
| Connector admin | `Connector admins` | `connector-admin`   |
| Monitoring      | `Monitoring`       | `monitoring`        |

A session records the hash of the mapping its Permissions were resolved under,
and startup ends every authenticated session issued under a different hash, so a
redeploy that changes the mapping reaches live sessions whether or not Redis kept
them. A restart that keeps the mapping keeps its hash and ends nothing.

**Reading the mapping.** `GET /api/admin/roles`, for a holder of `group:read`,
returns every Role with its Permissions and the Groups that confer it, each by
stable id and current `displayName`, with the Superuser Group marked — so an
operator can see what each Group grants without reading this configuration. It is
the only Role endpoint: none creates, changes or deletes a Role or a mapping
entry.

**Assigning a Role is Group membership.** A Role is held by being a direct member
of a mapped Group, which a connector writes over SCIM — so `group:write` on a
mapped Group is Role assignment, the Superuser Role's included, and a connector
token carrying it decides who holds those Roles. Every such membership change is
audited as `ROLE_GRANT` / `ROLE_REVOKE` with the Role's name. Removing a User from
a mapped Group (`PATCH`, `PUT`, or deleting the Group) ends its sessions once the
write commits; adding one applies at its next login. The Superuser Group cannot be
renamed or deleted and the Bootstrap Admin's membership of it is frozen; every
other mapped Group is writable and deletable, and a deleted one confers nothing
until the mapping is replaced.

### Logging

| Variable                | Default | Meaning                                                        |
| ----------------------- | ------- | -------------------------------------------------------------- |
| `LOG_FILE`              | (unset) | Also write ECS JSON to this file, rolling; unset means no file |
| `APP_ENVIRONMENT`       | `local` | `service.environment` on every record                          |
| `LOG_STRUCTURED_FORMAT` | `ecs`   | Console format; set empty for Boot's human-readable pattern    |

Records are ECS JSON on stdout. Each one carries `service.name` (`backend`),
`service.version` (the built project version), `service.environment`, and an
`@timestamp` in Singapore time (`2026-10-01T16:52:11.726+08:00`). Only the log is
in that zone: the JVM's default zone is not changed, so SCIM `meta` times and
audit times stay UTC (`...Z`). The scheduled jobs' cron expressions are evaluated
in the same `Asia/Singapore` zone, so a job's schedule and its records agree.

Every record emitted inside a request, or inside a scheduled-job run, carries
`trace.id` and `span.id`. The ids are minted here: tracing is on for correlation
only, nothing is exported and an inbound `traceparent` is ignored
(`src/main/resources/telemetry.yaml`).

With `LOG_FILE` set (deployed: `/var/log/backend/backend.json`, see
`/infra/README.md` under "View Logs") the same records are also written to that
file, one JSON object per line, rolled by date and size with a bounded total;
the rolling limits live in `src/main/resources/logging.yaml`. Stdout keeps
working. Local development and the tests leave it unset and write no file.

### SCIM release gate

| Variable           | Default | Meaning                                   |
| ------------------ | ------- | ----------------------------------------- |
| `APP_SCIM_ENABLED` | `true`  | Whether this deployment serves `/scim/v2` |

**On by default.** Every path needs a connector token: the discovery documents
(`ServiceProviderConfig`, `ResourceTypes`, `Schemas`) take any valid one, and the
`Users` and `Groups` resource endpoints the token's Permissions (ADR 0010). Set
`APP_SCIM_ENABLED=false` to turn the interface off: the whole `/scim/v2`
namespace then answers `404` — discovery included, and ahead of
authentication, so a valid connector token gets the same answer as none at all.

The value is not written in `application.yaml`: the default belongs to
`ScimSecurityConfig`, so an unset variable reaches the gate as open rather than as
whatever a replaced config file happens to say.

### Epic Login

Epic SMART on FHIR EHR launch Login (`/docs/adr/0013-epic-login.md`, the sole authority on it).
**Off by default**, and then nothing below is read or required: the app starts
with no Epic variable at all, and every `/api/auth/epic/**` path answers `404`
ahead of the session, CSRF and authorization checks, mirroring
`APP_SCIM_ENABLED=false`.

| Variable                         | Required when enabled | Default | Meaning                                                                   |
| -------------------------------- | --------------------- | ------- | ------------------------------------------------------------------------- |
| `APP_EPIC_ENABLED`               | —                     | `false` | The feature switch                                                        |
| `APP_EPIC_FHIR_BASE`             | yes                   | none    | The one allowed launch `iss`, sent as `aud`, and the prefix of `fhirUser` |
| `APP_EPIC_OAUTH_ISSUER`          | yes                   | none    | OIDC issuer, for discovery and the `id_token` `iss`                       |
| `APP_EPIC_CLIENT_ID`             | yes                   | none    | Epic client id                                                            |
| `APP_EPIC_REDIRECT_URI`          | yes                   | none    | Absolute callback URL as registered (path `/api/auth/epic/callback`)      |
| `APP_EPIC_CLIENT_KEY`            | yes                   | none    | Active signing key: EC P-384 private key, PKCS#8 PEM                      |
| `APP_EPIC_CLIENT_KEY_ID`         | yes                   | none    | The active key's `kid`                                                    |
| `APP_EPIC_CLIENT_NEXT_KEY`       | no                    | none    | Next key, published but never used to sign; set with its `kid`            |
| `APP_EPIC_CLIENT_NEXT_KEY_ID`    | no                    | none    | The next key's `kid`; must differ from the active one                     |
| `APP_EPIC_CONNECT_TIMEOUT`       | no                    | `2s`    | Connect timeout for every outbound Epic call                              |
| `APP_EPIC_READ_TIMEOUT`          | no                    | `5s`    | Read timeout for every outbound Epic call                                 |
| `APP_EPIC_MFA_EVIDENCE_REQUIRED` | no                    | `false` | Require MFA evidence in the `id_token`'s `amr` (D17); see below           |

`APP_EPIC_MFA_EVIDENCE_REQUIRED` stays off until Epic confirms it sends `amr` on
an EHR launch (ADR 0013, "Open items"). Off, the Epic organisation's MFA is an
attestation and every Epic `LOGIN_SUCCESS` records the factor `idp-attested`.
On, a token whose `amr` names no RFC 8176 second factor and not `mfa` is
refused as `INVALID_CLAIMS`, and the factor recorded is the one `amr` named;
ADR 0013 lists the factors accepted.

**No default credentials**: this repository is public, so no URL, client id or
key has a fallback anywhere. With the switch on, startup fails fast when a
required variable is missing or blank, when a URL is not an absolute `https`
URL (only the `dev` profile may use `http`, for a local launcher), when a key is
not an EC P-384 private key in PKCS#8 PEM, when the next key and its `kid` are
not set together, when the two `kid`s are equal, or when a timeout is not
positive. An empty variable counts as unset. The error names the variable and
the rule, **never the value**, and the bound settings print with both keys
redacted. Startup does not contact Epic: discovery runs on the first launch and
is kept for 24 hours, so a wrong issuer or an unreachable Epic shows on the first
launch — as the login page's "temporarily unavailable" notice when Epic gives no
answer within the timeouts or answers `5xx` — never as a failed deploy (ADR 0013).

Generate a key with `openssl genpkey -algorithm EC -pkeyopt
ec_paramgen_curve:P-384`. The PEM may be given on one line, with or without its
line breaks escaped as `\n`. Never commit a key.

The startup record carries `app.epic.enabled` and, while it is on, the active
and next `kid` (`app.epic.client_key_id`, `app.epic.client_next_key_id`), so a
redeploy that promotes a key leaves a record. It never carries a URL, the
client id or key material.

With the switch on, `GET /api/auth/epic/jwks.json` is public and serves the
active key and then the next key, if one is set, as EC P-384 public keys for
ES384 under their `kid`s, with no private parameter. Epic verifies our client
assertions against it. Client assertions are signed by the active key only,
through `ClientAssertionSigner`. The runbook for promoting a key is
`/infra/README.md`, "Signing-key promotion".

```bash
curl -i localhost:8080/api/auth/epic/jwks.json
```

#### Local Epic launcher

A real SMART EHR launch, locally and without Epic: the SMART Health IT launcher
(`smartonfhir/smart-launcher-2`) stands in for Epic as the `smart-launcher`
service in `compose.yaml`. It is behind the `epic-launcher` compose profile, so
`make infra-up` and a plain `docker compose up` never start it. From the
repository root:

```bash
make epic-launcher-up     # SQL Server, Redis and the launcher on http://localhost:9009
make epic-launcher-down   # stops all three; `make infra-down` leaves the launcher up
```

The launcher is plain `http`, so the backend must run in the **`dev` profile**
(beside `local`, which runs the migrations),
the only one that accepts `http` Epic URLs (D21). That profile alone also
accepts the launcher's relative `fhirUser` (`Practitioner/{id}`); outside it,
`fhirUser` must be the absolute `{APP_EPIC_FHIR_BASE}/Practitioner/{id}`. Never
use either allowance anywhere but a developer machine. Run the backend with
these in place of the Epic lines in `.env`:

| Variable                 | Value for the launcher                           |
| ------------------------ | ------------------------------------------------ |
| `SPRING_PROFILES_ACTIVE` | `local,dev`                                      |
| `APP_EPIC_ENABLED`       | `true`                                           |
| `APP_EPIC_FHIR_BASE`     | `http://localhost:9009/v/r4/fhir`                |
| `APP_EPIC_OAUTH_ISSUER`  | `http://localhost:9009/v/r4/fhir` (the same URL) |
| `APP_EPIC_CLIENT_ID`     | any value; the launcher accepts any client id    |
| `APP_EPIC_REDIRECT_URI`  | `http://localhost:5173/api/auth/epic/callback`   |
| `APP_EPIC_CLIENT_KEY`    | a key generated for the purpose (see above)      |
| `APP_EPIC_CLIENT_KEY_ID` | any `kid`, e.g. `local-1`                        |

The FHIR base is the `iss` the launcher sends on a provider EHR launch, and its
discovery document names that same URL as the issuer. The redirect URI goes
through the Vite dev server (`make dev`), so the callback's `302 /` lands on
the SPA. Generate the key per machine and keep it out of the repository, e.g.
`export APP_EPIC_CLIENT_KEY="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384)"`.

To launch by hand, provision a User over SCIM whose `userName` is the
Practitioner ID you will launch as. A connector's write leaves a password change
pending, which confines the User's password Login to `/change-password` but not
its Epic Login: an Epic Login presents no password of ours, so it lands on
`/showcase` whether or not the change is pending (ADR 0008's 2026-10-09
addendum). Then, in the launcher UI at
`http://localhost:9009`:

- **Launch type** `Provider EHR Launch`; pick a patient, and set the provider to
  that Practitioner ID. Turn on "Skip login" and "Skip authorization".
- **Client** `Confidential asymmetric`, with the JWKS URL
  `http://host.docker.internal:8080/api/auth/epic/jwks.json`: the launcher
  verifies our client assertion against it, from inside its container.
- **App launch URL** `http://localhost:5173/api/auth/epic/launch`.

Launching lands on `/showcase`, signed in as that User with its Groups'
Permissions. `make epic-integration-test` runs the same launch as a Playwright
spec, which is Epic Login's E2E gate (`frontend/AGENTS.md`); it generates its own
key and sets all of the above itself.

### Audit trail retention

| Variable                       | Default         | Meaning                                   |
| ------------------------------ | --------------- | ----------------------------------------- |
| `APP_AUDIT_RETENTION_PERIOD`   | `365d` (1 year) | How long a recorded audit event is kept   |
| `APP_AUDIT_RETENTION_SCHEDULE` | `0 30 3 * * *`  | When the retention job runs (Spring cron) |

The **floor is 90 days**, and it is enforced rather than advised: a configured
period below it fails startup with the value in the message, instead of quietly
keeping less history than an investigation needs. `90d` itself is allowed. Neither
value appears in `application.yaml` — both defaults belong to
`AuditRetentionPolicy`, so an unset variable reaches the rule as unset and "the
default is one year" is a fact about the rule rather than about a config file.

The job logs its schedule at startup, and each run logs one summary line, its
`job-end` (`app.event.action: audit.retention`): `event.outcome`,
`batch.job.status` (`completed` or `failed`), what it deleted as
`record=[{type: audit_event, deleted: <n>, batches: <m>}]`, and
`event.duration_ms`. A run that deleted nothing reports too — "nothing had aged
out" and "the job has not run for a month" are different facts.

A run deletes in batches of at most 4,000 events (`DELETE TOP (4000)`), each in a
transaction of its own on the retention pool with a 60 s query timeout, until a
batch comes back short. A batch stays below SQL Server's lock-escalation threshold
of 5,000 locks, so it holds row locks only and an audit append never waits behind
it (ADR 0015). A failed run reports what it had committed: every batch before the
one that failed, or all of them when the run's lock transaction failed afterwards,
since each batch commits on the retention pool before that transaction does; the
next run deletes the rest. The job is serialized on its own `audit-retention` row
in `scheduled_job_locks`, as the dormancy job is (see below), and the lock is held
by the run's main-pool transaction across every batch: with several instances on
the same cron one run deletes, and each other — even one started between two
batches — writes one `skipped` line (`event.reason: lock-held`) and deletes
nothing.

### Audit append pool

| Variable                 | Default | Meaning                                                            |
| ------------------------ | ------- | ------------------------------------------------------------------ |
| `APP_AUDIT_POOL_SIZE`    | `2`     | Connections in the pool fail-open audit appends commit on (2 or 3) |
| `APP_AUDIT_POOL_TIMEOUT` | `2s`    | How long such an append waits for one before alerting (≥ `250ms`)  |

A refusal's audit event — a rejected Login, a refused SCIM write, a refused
token rotation — and a post-commit Session revocation's are appended
**fail-open**, in a transaction of their own (ADR 0004). They commit on a small
pool of their own, never on the main one: a refusing request still holds its
main-pool connection (a rejected Login holds the User row's lock too), so an
append that needed a second connection from the same pool would, under enough
overlapping refusals, wait out the pool's timeout for a connection none of them
would release. Accepted (fail-closed) events still join their request's
transaction on the main pool.

The pool has **no credentials of its own** and no URL setting: it opens its
connections as the main pool does, with the same URL, driver properties and app
login (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`), so appends run
as the app login like every other request's statement, never as the owner login.
It starts lazily, on the first fail-open append. An append that
cannot get a connection within the timeout is lost behind the `ERROR`
`audit.append` alert, as any failed fail-open append is. The pool is named
`audit-append` in Hikari's log lines.

### Operational telemetry

| Variable                 | Default   | Meaning                                                |
| ------------------------ | --------- | ------------------------------------------------------ |
| `MANAGEMENT_SERVER_PORT` | the app's | Serve `/actuator/**` on this port instead of the app's |

`/actuator/prometheus` is the metrics scrape, and it requires the `ops:read`
Permission (the Monitoring Role holds it alone): a session without it gets `403`,
and a connector token gets `401` because the SCIM bearer chain
does not cover `/actuator`. Setting `MANAGEMENT_SERVER_PORT` moves all of actuator
to that port under the same rules: `/actuator/health` stays public and every other
actuator path keeps requiring `ops:read`. The exposure and histogram settings live in `src/main/resources/telemetry.yaml`,
which the test configuration imports too. The tag policy (what a metric may be
labelled with) lives in `ScimRequestObservationConvention`. The alert rules are
`ops/prometheus/alerts.yaml`. Deployment steps, the internal-port setup and the
alert table are in `/infra/README.md` under "Operational telemetry".

A scheduled job reports its runs through `ScheduledJobMetrics`
(`app_job_runs_total{job,outcome}`, `app_job_last_success_seconds{job}`). The audit
retention job is `job="audit-retention"` and the dormancy job `job="dormancy"` (which
the alert rules select on). Each run is also its own trace, so every record
a run emits carries one `trace.id`, and is timed as `app_job_run_seconds{job}`. The
dormancy job also counts what it changed: `app_dormancy_users_locked_total` and
`app_dormancy_users_roles_revoked_total`, registered at zero from startup, so a mass
lockout or revocation is a spike on an existing series.

### Dormancy

| Variable                              | Default           | Meaning                                                                                                        |
| ------------------------------------- | ----------------- | -------------------------------------------------------------------------------------------------------------- |
| `APP_DORMANCY_LOCKOUT_WINDOW`         | `90d` (90 days)   | How long a User may go without logging in before it is locked for dormancy                                     |
| `APP_DORMANCY_ROLE_REVOCATION_WINDOW` | `180d` (180 days) | How long before its direct membership of every mapped Group is removed; must be longer than the lockout window |

One daily job (04:00 `Asia/Singapore`) applies them, both measured from the User's
last successful login — or its last reactivation or Unlock, or its creation if it
has had none of those. A login made while a password change is required of the
User does not count; the completed change does (see
[Required password change](#required-password-change)). Each run, in order:

- **Role revocation** removes a User past the role-revocation window from every
  Group the role mapping names — unmapped memberships stay — advances each Group's
  and the User's versions, ends the User's sessions and records one
  `DORMANCY_ROLE_REVOCATION` audit event naming the Roles lost.
- **Lockout** locks every unlocked User past the lockout window with lock cause
  `DORMANCY`, ends its sessions and records a `DORMANCY_LOCKOUT` audit event,
  logged at `WARN`. A User already locked keeps its lock and its cause.

The job never writes `active` and never deletes anything. A dormancy lock never
lifts on its own: only an **Unlock** (`user:write`) ends it, which also restarts the
dormancy window and requires a password change of a User that has one. The
Accounts page shows the lock's cause. The Bootstrap Admin is never processed. A
connector re-asserting `active=true` resets nothing, and a connector that re-adds
a mapped membership of a User that is still dormant sees it removed again on the
next run.

The job is serialized on its own row in `scheduled_job_locks`
(`SELECT … WITH (UPDLOCK, ROWLOCK, READPAST)`, held for the run's transaction), so two runs
never overlap across instances and a run that finds the job already running skips.
A zero or negative window, or a role-revocation window not longer than the lockout
window, fails startup. Neither default appears in `application.yaml` — both belong
to `DormancyPolicy`. The startup record states the job's cron, zone and both
windows; every run logs `job-start` and `job-end` (`event.action:
identity.dormancy`), the end carrying `dormancy.locked_count` and
`dormancy.roles_revoked_count`, including a run that skipped or changed nobody.

With the development fixtures on (`APP_DEV_FIXTURES_ENABLED=true`, as
`.env.example` sets) the job also runs once at startup, after seeding has
backdated the `dormant` fixture User past the lockout window, so a local run and
the e2e suite see a dormancy lock without waiting for 04:00. That run applies to
every User, as the nightly one would.

### Required password change

A password change is required of a User — the change-required flag — by every
connector password write (SCIM create, PUT or PATCH carrying `password`), by a
**Force password change** and by an **Unlock** of an account that has a password
(each requiring `user:write`). Only a successful self-service change
(`POST /api/auth/change-password`) clears it; a connector write never does. While
flagged, a session issued by a password Login may call `GET /api/auth/me`, the
change and `DELETE /api/auth/logout`, and nothing else — `/api/admin/**`
included, whatever Permissions the User's Groups confer. An Epic Login is not
confined by the flag: it presents no password of ours, so it receives the
authorities the User would hold unflagged, and leaves the flag set (ADR 0008's
2026-10-09 addendum). Any User may change its password at any time through the
same endpoint.

There is no deadline for the change. Instead, a flagged User's password logins do
not move its dormancy basis (its Epic Logins, not being confined, do), so a User
that keeps logging in with an imposed credential and never replaces it is
locked by the dormancy job once the lockout window has
passed since its creation, last real login, reactivation or Unlock. The completed
change moves the basis.

### Database logins

The service reaches SQL Server as **three logins**, each holding only its own
privileges, and nothing switches role on a connection (ADR 0015, "Runtime
model"; `NoRoleSwitchTest`, in the `arch` tests, holds that no source
or configuration file issues `SET ROLE`):

| Login               | Variables                                                    | Used by                                                                     | Member of                 |
| ------------------- | ------------------------------------------------------------ | --------------------------------------------------------------------------- | ------------------------- |
| **app login**       | `DATABASE_USERNAME`, `DATABASE_PASSWORD`                     | the main pool (`app`) and the audit append pool (`audit-append`)            | `backend_app`             |
| **retention login** | `DATABASE_RETENTION_USERNAME`, `DATABASE_RETENTION_PASSWORD` | the audit retention job's own pool (`retention`) and nothing else           | `backend_audit_retention` |
| **owner login**     | `DATABASE_OWNER_USERNAME`, `DATABASE_OWNER_PASSWORD`         | the migrations, and nothing else: never the running service in a deployment | — (owns the database)     |

`DATABASE_URL` is the one database all three reach. **Neither it nor any of the six
login variables has a default** — this repository is public — so a missing one
fails startup. The test JVMs drop all of them (`pom.xml`).

- **`backend_app`** holds full DML on the tables whose lifecycle the application
  owns (the `scim_*` directory tables and `user_counters`) and narrower grants
  where a table's rule is narrower: `SELECT`/`INSERT` only on `scim_tombstones`
  and `audit_events`, no `UPDATE` on `scim_user_password_history`, and
  `SELECT`/`UPDATE` only on `scheduled_job_locks`, so the app login cannot remove
  the row a job serializes on. An `UPDATE` or `DELETE` of a recorded event from
  application code is refused by the server. The migrations' `GRANT` statements
  are the exact list; a later migration that adds a table the application writes
  must grant `backend_app` on it. A refusal is SQL Server error 229.
- **`backend_audit_retention`** holds `SELECT`/`DELETE` on `audit_events` and
  nothing else — no `UPDATE`. Only the audit retention adapter reaches the
  retention pool.

Beside the grants the audit table carries an `INSTEAD OF UPDATE, DELETE` trigger
that refuses **every** `UPDATE`, from every principal, and every `DELETE` unless
the caller is a member of `backend_audit_retention` and not of `db_owner`, with
error 50001. That second half is what refuses the owner login, which owns the
database — so the grants do not bind it — and a sysadmin, which enters as its
owner. Append-only therefore survives a console session or a misused owner
credential. `AuditAppendOnlyIntegrationTests` connects as each login and asserts
each refusal.

The pools: how each reaches the database is one module's, `DatabaseConnections`
(`database.config`): the URL, the driver, and every driver property under
`spring.datasource.hikari.data-source-properties` — set in
`src/main/resources/database.yaml`: `encrypt=true`, `sslProtocol=TLSv1.2` and
`loginTimeout=15`, with the trust store to come — reach the main, audit append
and retention pools alike, and the migrate step's owner connection too
(`MigrationConnection`). Set such a property there, for the service and the
migrate step both, and never on one pool. A pool states only its name, login,
size and connection timeout. The main pool binds every
`spring.datasource.hikari.*` setting as before, and is named `app`; the retention
pool holds two connections and binds its login, and only its login, from
`app.datasource.retention.*`. Both wait at most 10 s for a
connection, and both appear by name in the `db` health indicator
(`dataSource`, `auditRetentionDataSource`) and in the `hikaricp_*` series
(`pool="app"`, `pool="retention"`). The retention pool starts on first use — its
first run, or the first health check.

A retention run takes the scheduled job's lock on the main pool, as the app
login, and holds it for the whole run; its deletes commit in transactions of the
retention pool's own transaction manager inside that run (ADR 0005).

#### Where migrations run

Flyway runs as the owner login, and **only in the `local`, `test` and `migrate`
profiles**; every other profile — a deployment's included — runs with it off,
and `ddl-auto: validate` still fails startup on a schema that drifted.
`MigrationProfilesTests` holds that, and that the deployed configuration reads no
owner credential.

- **A deployment** runs the **migrate step** before the service: the same JAR,
  started at its migrate entry point, with no web server, and exits.

  ```bash
  java -Dloader.main=com.example.backend.database.config.MigrationStep \
      -cp backend.jar org.springframework.boot.loader.launch.PropertiesLauncher
  ```

  It needs `DATABASE_URL`, `DATABASE_OWNER_USERNAME`, `DATABASE_OWNER_PASSWORD`
  and the two other logins' **names** (`DATABASE_USERNAME`,
  `DATABASE_RETENTION_USERNAME`) — no other password — and whatever
  `spring.datasource.hikari.data-source-properties.*` the service is given, so
  its connection is opened as the pools' are. A failed migration exits
  non-zero, and the deploy does not start the service (`/infra/README.md`,
  "Migrate step"). The service is then given the app and retention logins only.

- **Locally**, `SPRING_PROFILES_ACTIVE=local` (`.env.example`) migrates at
  startup; **the tests** migrate each context's database as the owner login
  (`ContainerTestConfiguration`).

The migrations learn the app and retention logins' names as the placeholders
`app_login` and `retention_login`, and `V1` creates a database user `FOR LOGIN`
each and makes it a member of its role, quoting the name with `QUOTENAME`. `MigrationLoginNames` refuses a name that does not match
`^[a-z][a-z0-9_]{2,30}$`, or the two naming one login, before Flyway runs a
statement.

#### Local setup

`compose.yaml` runs **SQL Server 2022** (CU27, Developer Edition, pinned by tag
and digest, `linux/amd64`) on `localhost:1433`, capped at 2 GB, with a health
check, beside Redis. Its one-shot `sqlserver-init` service, in a profile of its own
so a plain `up` leaves it out, is run in the foreground by `make infra-up` once SQL
Server is healthy — `docker compose run --rm sqlserver-init`, whose exit status is
the init's, since `up --wait` does not wait for a one-shot to finish
(`scripts/local-db-init.sh` and `local-db-init.sql`): it creates the
`backend` database with its creation options — collation
`Latin1_General_100_BIN2_UTF8`, compatibility level 160, `READ_COMMITTED_SNAPSHOT`
and `ALLOW_SNAPSHOT_ISOLATION` on, none of which a migration can set — and the
owner, app and retention logins from `.env`, and makes the owner login the
database's owner. It is idempotent: it leaves what exists and resets each
login's password, so `make infra-up` after changing `.env` applies it. It echoes
no secret: values reach `sqlcmd` as scripting variables from the environment,
never as arguments, and `sqlcmd` runs without `-e`. The roles, the app and
retention logins' database users and their grants come from `V1`, so the init
grants nothing. A deployment creates the database and the logins with its own
bootstrap instead.

The server keeps its default collation and only the database is collated
`Latin1_General_100_BIN2_UTF8`: a server collated `BIN2_UTF8` cannot load the
internal procedures behind `sp_getapplock`, which the seed lock takes.

The `.env` variables it reads:

| Variable                                                     | What it is                                                                                                                                                                                                          |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MSSQL_SA_PASSWORD`                                          | The local server's `sa` password, read by compose for the server and its init only; the backend never sees it. SQL Server's policy applies: 8+ characters from three of upper case, lower case, digits and symbols. |
| `DATABASE_URL`                                               | `jdbc:sqlserver://localhost:1433;databaseName=backend`. No default.                                                                                                                                                 |
| `DATABASE_OWNER_USERNAME`, `DATABASE_OWNER_PASSWORD`         | The owner login, created by the init and made the database's owner.                                                                                                                                                 |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD`                     | The app login.                                                                                                                                                                                                      |
| `DATABASE_RETENTION_USERNAME`, `DATABASE_RETENTION_PASSWORD` | The retention login.                                                                                                                                                                                                |

Login names must match `^[a-z][a-z0-9_]{2,30}$` and be three different names; a
password must hold no quote. The local server's certificate is self-signed, so
the `local` profile alone sets `trustServerCertificate=true`; everywhere else the
certificate is validated, and `TrustServerCertificateTest` fails the build if
that setting appears in any other profile, resource or deployment script. The
connection is encrypted either way (`database.yaml`).

`make dev` checks that SQL Server answers at the host and port of
`DATABASE_URL` (1433 by default) before it starts anything.

### Session storage

Sessions are stored through Spring Session's **indexed** Redis repository, which
keeps a per-principal index. That index is what lets the service end a User's
sessions — on deactivation, lockout, a forced password change, or a new login
under one session per User — so the setting is a requirement rather than a preference:
with the default repository the application does not start. It is configured in
`src/main/resources/session.yaml`, imported by both the main and the test
`application.yaml` so the two cannot drift. Two consequences for
a deployment — the Redis instance is not interchangeable with a plain cache
(session keys and one index set per signed-in account), and startup does not try
to `CONFIG SET notify-keyspace-events`, because ElastiCache refuses `CONFIG`. Set
`notify-keyspace-events` in the cache parameter group if you want expiry events;
without them expired sessions are reaped by the repository's cleanup cron.

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
`/showcase` to `index.html`; `/api/**` and `/actuator/**` remain
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
`contract`, `observability`, `counter`, `database`, `lifecycle`, `scheduling`, `web`);
`BackendApplicationTests`, at the package root, runs only in the broad gate.

The test JVMs drop every `.env.example` key from their environment
(`excludedEnvironmentVariables` in `pom.xml`), so the results do not depend on
what the shell exported.

Deletions, 2026-10-13: 17 test methods whose oracle and failure domain a
surviving test already held — seven Epic meter `+1` checks
(`EpicLoginRedactionIntegrationTests.theEpicLoginMetersMoveUnderTheExpectedTags`
asserts every path's whole meter delta), three Group version-bump service
tests that read the in-memory fake (`RepresentationChangeTests` owns the rule),
three logout duplicates within `AuthControllerTests`, two discovery-needs-a-token
copies (`ScimDiscoveryIntegrationTests`), one read-only-create refusal
(`ScimUserProvisioningIntegrationTests`), and one Bootstrap Admin role check
left over from `ROLE_ADMIN`'s retirement. The baseline gate after, on c4be0f8:
`Tests run: 4751, Failures: 0, Errors: 0, Skipped: 0`, 28 contexts started.

Scheduled-job run module, 2026-10-13 (ADR 0005 amendment): two service tests
that re-tested the lock's per-job keying (`anotherJobHoldingItsLockDoesNotStopThisOne`
in `DormancyServiceTests`, `anotherJobsLockBeingHeldDoesNotStopARetentionRun` in
`AuditRetentionServiceTests`) moved into the module's own
`ScheduledJobRunServiceTests` (fast gate, six tests). The new
`ScheduledJobRunIntegrationTests` (five tests) joins the shared context. The
baseline gate after: `Tests run: 4813, Failures: 0, Errors: 0, Skipped: 0`, up from
4803 on bc8bc92.

Separate database logins, 2026-10-14 (#66): the test contexts now connect as the
app and retention logins and migrate as the owner login (`ContainerTestConfiguration`),
with `DatabaseLogins` for the tests that must speak as another principal. New:
`DatabasePoolsIntegrationTests` (joins the shared context), `MigrationStepIntegrationTests`
(starts three Flyway-only contexts of the migrate step, no service context),
`DataSourceConfigTests` and `AuditRetentionPoolConfigTests` (application context
runners, no container), and `MigrationLoginNamesTests`, `MigrationProfilesTests` and
`NoRoleSwitchTest` (fast gate). The role-switch assertion
`theDeployedConfigurationMakesTheRuntimeRoleTheApplicationsOwn` was removed with the
switch it pinned. The baseline gate after: `Tests run: 4860, Failures: 0, Errors: 0,
Skipped: 0`, up from 4813 on 3d54d0d; 2:24 min against 2:48.

Logging hardening, 2026-10-14 (#64, ADR 0003): three classes join the fast gate —
`AuditRetentionRunLogTests` (7 tests), `DatabaseFailureCategoryTests` (26) and
`DeadlockRetryAdapterTests` (12) — and three full-context classes join the shared
configuration, adding no context of their own: `DatabaseFailureLogIntegrationTests`
(7 tests, 3.7 s), `ScimDeadlockRetryIntegrationTests` (2 tests, 3.7 s, a forced
PostgreSQL deadlock) and `StartupLogConnectionDetailsTests` (2 tests, 38.3 s; each
runs the migrate step and then `BackendApplication` in child JVMs against a
database of its own, which makes it the slowest of the three).
`ScheduledJobRegistrationTests` gains three cases for a failed run's committed
progress, and `LogEventTests` lost three with the retired `jobSummary` shape. The
baseline gate after: `Tests run: 4944, Failures: 0, Errors: 0, Skipped: 0`, up from
4863 measured on a226ae1 (the entry above reads 4860), with 32 context starts, as on
a226ae1, and Semgrep 0 findings; 7:00 min against 4:28.

Database connections module, 2026-10-14 (architecture review B24): two classes
join the fast gate — `DatabaseConnectionsTests` (11 tests) and
`MigrationConnectionTests` (5) — and `EveryPoolConnectsAlikeTests` (3, an
application context runner, no container) holds every pool to the main pool's
driver properties. `MigrationStepIntegrationTests` gains one case (a driver
property reaches the owner's connection), `AuditRetentionPoolConfigTests` gains
two, and `AuditAppendPoolConfigTests` loses its connection-init-sql case to
`EveryPoolConnectsAlikeTests`. Scoped PIT over the changed classes: 69 mutants,
69 killed, line coverage 54/54. The baseline gate after: `Tests run: 4988,
Failures: 0, Errors: 0, Skipped: 0`, up from 4967 on 98f8375, Semgrep 0
findings, no context start added.

Deadlock retry coverage and its 503, 2026-10-14 (architecture review B23, ADR 0015
as-13): `DeadlockRetryRulesTests` (8 tests, planted classes, no context) joins the
fast gate beside the seven new `ArchitectureTest` rules (86, up from 79), and `ScimExceptionHandlerDeadlockTests` (7) is a unit test.
`DeadlockRetryAdapterTests` gains four cases (16), `ApiExceptionHandlerTests` three
(17). `ScimDeadlockRetryIntegrationTests` (3 tests) now drives its second deadlock
over HTTP to the `503` and adds a token rotation retried through `ForcedDeadlock`,
the trigger the contract fixtures use for the documented `503`s:
`ScimConformanceFixtureTests` gains eight (428) and `ApiContractFixtureTests` two
(29). No class adds a configuration; the context start count reads 33 in two runs
against 31 on df51e6b, the difference being where the forks place the two
`@DirtiesContext` classes, which decides how often the shared context reboots.
Scoped PIT over `DeadlockRetryAdapter`, `ExhaustedRetry`, `ApiExceptionHandler`
and `ScimExceptionHandler`: 247 mutants, 247 detected (one of them a timeout in
the unreadable-body handler's cause loop, which predates this change), line
coverage 163/165, the two lines being `ExhaustedRetry`'s private constructor. The
baseline gate after: `Tests run: 5033, Failures: 0, Errors: 0, Skipped: 0`, up
from 4993 on df51e6b, Semgrep 0 findings; 4:32 min against 4:28.

SQL Server switchover 1/5, 2026-10-14 (#68, ADR 0015; integration branch
`mssql-cutover`): the suite runs on one SQL Server 2022 CU27 container per test JVM
(`ContainerTestConfiguration`), each context on a database of its own created with
the deployment's options. Before, on PostgreSQL at 6e8a882: `./scripts/verify.sh`
308 s wall-clock (Maven `Total time: 04:48 min`), `Tests run: 5033, Failures: 0,
Errors: 0, Skipped: 0`, 32 context starts, Semgrep 0 findings. After, on SQL Server:
Maven `Total time: 06:23 min` (385 s), `Tests run: 5062, Failures: 46, Errors: 166,
Skipped: 0`, 31 context starts (32, 31, 33 and 31 across four runs — the forks place
the `@DirtiesContext` classes differently — with no configuration added: neither new
full-context class starts one), Semgrep 0 findings. The failures are the suites the
next switchover tickets port (locks, audit listing and error mapping in #69, the
SCIM query SQL in #70, the remaining PostgreSQL SQL in tests in #72), and they also
make the run slower than it will be: a failing wait runs to its patience. New:
`ScimSchemaUniquenessIntegrationTests` (12 tests) and `UtcStorageIntegrationTests`
(3) join the shared context; `TrustServerCertificateTest` (5) joins the fast gate;
`DatabaseUrlTests` (2) starts the deployed configuration and fails it, opening no
context; `DataSourceConfigTests` gains four (a missing URL; scoped PIT over
`DataSourceConfig`: 20 mutants, 20 killed, line coverage 10/10). `ConnectorTokenPermissionsMigrationTests` was removed with the PostgreSQL
`V6` it migrated through: the T-SQL `V1` is the only migration, and a fresh
database the only starting state.

SQL Server switchover 2/5, 2026-10-14 (#69, ADR 0015; integration branch
`mssql-cutover`): locks, isolation, audit and error surfacing on SQL Server. Three
classes join the fast gate, each over `ScriptedJdbc`, a JDBC fake at the driver
boundary: `ScheduledJobLockAdapterTests` (7 tests), `ScimSeedLockAdapterTests` (6)
and `AuditEventRetentionAdapterTests` (3); the fast gate runs 3,389 tests.
`UserCounterSerializationIntegrationTests` (1 test) joins the shared configuration
and starts no context of its own. Deleted:
`DatabaseFailureCategoryTests.anyInvalidAuthorizationStateIsACertAuthFailure`, which
pinned PostgreSQL's `28000` state (`sqlServersRefusedLoginIsACertAuthFailure` now
pins SQL Server's error 18456), and
`AuditRetentionSerializationIntegrationTests.ofTwoOverlappingRunsOneDeletesAndTheOtherSkipsLockHeldDeletingNothing`,
replaced by the two batching cases that hold a run between its batches. Two
`EcsErrorFieldsCustomizerTests` cases were renamed for the redacted text's new
`SQL Server error <n>` wording. Grown: `DatabaseFailureCategoryTests` (39),
`AuditRetentionServiceTests` (+5), `AuditRetentionRunLogTests` (+2),
`AuditEventReadAdapterIntegrationTests` (+2), `AuditListingIntegrationTests`,
`ScheduledJobRunIntegrationTests` and `ScimSeedIntegrationTests` (+1 each). Scoped
PIT over `DatabaseFailureCategory`, `SqlExceptionRedaction`, `ScheduledJobLockAdapter`,
`ScimSeedLockAdapter`, `AuditRetentionService`, `AuditEventRetentionAdapter`,
`DeadlockRetryAdapter` and `TimedJdbc`, from the nine touched unit test classes: 242
mutants, 242 detected (241 killed, 1 timed out), line coverage 161/165, the four
lines being the private constructors of `SqlExceptionRedaction` and `TimedJdbc`.
Before, on this branch with #68 alone:
`Tests run: 5062, Failures: 48, Errors: 166, Skipped: 0` (#68's entry above
records 46 failures from its own run), 31 context starts, Maven `Total time:
06:34 min`. After: `Tests run: 5100, Failures: 23, Errors: 144, Skipped: 0`, 33
context starts (31 and 32 in earlier runs of the same change — fork placement, no
configuration added), `Total time: 07:23 min`. Of the 48 failures and 166 errors
red at that baseline (#68's 46 and 166, plus two its own run did not see), 24
failures and 22 errors now pass and one failure was deleted with its rewrite; the
23 failures and 144 errors left were all red before, in the suites #70 (the SCIM
query SQL) and #72 (PostgreSQL SQL and UUID read-backs in test setup) port, and no
test newly fails. `./scripts/verify.sh` stops at the red tests before its scan, so
the tests ran as `./mvnw -B clean verify -Dmaven.test.failure.ignore=true` and the
scan as `./scripts/semgrep.sh`: 0 findings before, and
`Ran 153 rules on 791 files: 0 findings` after.

Live layers, run by hand and never by a gate: `npm run test:e2e` in
`frontend/`, `make integration-test` and `make epic-integration-test` from the
repository root, and the Semgrep registry download inside `./scripts/semgrep.sh`.

Build the deployable container, with the SPA in it, from the repository root:

```bash
make container
```
