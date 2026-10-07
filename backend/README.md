# Backend

A Java 25 / Spring Boot backend with HTTP sessions persisted in Redis and the
identity directory, audit trail and per-User counters persisted in PostgreSQL.
Identities are provisioned by an external directory over a SCIM 2.0 interface
(`/scim/v2`); the session-authenticated application API lives under `/api`.
Spring Session replaces the servlet container's in-memory session, so session
state can survive application restarts and be shared by multiple application
instances.

## Prerequisites

- Java 25
- Maven — not required; the checked-in wrapper (`./mvnw`) downloads and
  checksum-verifies the release pinned in `.mvn/wrapper/maven-wrapper.properties`
- Docker with Docker Compose (recommended for local Redis and PostgreSQL)

## Run locally

Start Redis and PostgreSQL:

```bash
docker compose up -d redis postgres
```

Start the application:

```bash
./mvnw spring-boot:run
```

Spring Boot does not read `.env` itself, so export it first (`make dev` from the
repository root does). Without `APP_DEV_FIXTURES_ENABLED=true` and
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
variables in your shell. The defaults connect to Redis at `localhost:6379`,
PostgreSQL at `localhost:5432`, and expire sessions after 15 minutes of
inactivity — the same value every environment uses, including deployed ones
(`infra/infrastructure.yaml`), so the SPA can rely on a single window. Override
`DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` in deployed
environments.

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

Epic SMART on FHIR EHR launch Login (`/docs/epic-smart-login.md`, section 5).
**Off by default**, and then nothing below is read or required: the app starts
with no Epic variable at all, and every `/api/auth/epic/**` path answers `404`
ahead of the session, CSRF and authorization checks, mirroring
`APP_SCIM_ENABLED=false`.

| Variable                      | Required when enabled | Default | Meaning                                                                   |
| ----------------------------- | --------------------- | ------- | ------------------------------------------------------------------------- |
| `APP_EPIC_ENABLED`            | —                     | `false` | The feature switch                                                        |
| `APP_EPIC_FHIR_BASE`          | yes                   | none    | The one allowed launch `iss`, sent as `aud`, and the prefix of `fhirUser` |
| `APP_EPIC_OAUTH_ISSUER`       | yes                   | none    | OIDC issuer, for discovery and the `id_token` `iss`                       |
| `APP_EPIC_CLIENT_ID`          | yes                   | none    | Epic client id                                                            |
| `APP_EPIC_REDIRECT_URI`       | yes                   | none    | Absolute callback URL as registered (path `/api/auth/epic/callback`)      |
| `APP_EPIC_CLIENT_KEY`         | yes                   | none    | Active signing key: EC P-384 private key, PKCS#8 PEM                      |
| `APP_EPIC_CLIENT_KEY_ID`      | yes                   | none    | The active key's `kid`                                                    |
| `APP_EPIC_CLIENT_NEXT_KEY`    | no                    | none    | Next key, published but never used to sign; set with its `kid`            |
| `APP_EPIC_CLIENT_NEXT_KEY_ID` | no                    | none    | The next key's `kid`; must differ from the active one                     |
| `APP_EPIC_CONNECT_TIMEOUT`    | no                    | `2s`    | Connect timeout for every outbound Epic call                              |
| `APP_EPIC_READ_TIMEOUT`       | no                    | `5s`    | Read timeout for every outbound Epic call                                 |

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
make epic-launcher-up     # Postgres, Redis and the launcher on http://localhost:9009
make epic-launcher-down   # stops all three; `make infra-down` leaves the launcher up
```

The launcher is plain `http`, so the backend must run in the **`dev` profile**,
the only one that accepts `http` Epic URLs (D21). That profile alone also
accepts the launcher's relative `fhirUser` (`Practitioner/{id}`); outside it,
`fhirUser` must be the absolute `{APP_EPIC_FHIR_BASE}/Practitioner/{id}`. Never
use either allowance anywhere but a developer machine. Run the backend with
these in place of the Epic lines in `.env`:

| Variable                 | Value for the launcher                           |
| ------------------------ | ------------------------------------------------ |
| `SPRING_PROFILES_ACTIVE` | `dev`                                            |
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
Practitioner ID you will launch as, and let it set its own password once (a
connector's write leaves a change pending, which confines every session of the
User, an Epic one included, to `/change-password`). Then, in the launcher UI at
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

Each run logs its schedule at startup and, per run, the rows it deleted and how
long it took (`event.action: audit.retention`). A run that deleted nothing is
logged too — "nothing had aged out" and "the job has not run for a month" are
different facts. The job is serialized on its own `audit-retention` row in
`scheduled_job_locks`, as the dormancy job is (see below): with several instances
on the same cron one run deletes, and the others end `job-end` with
`event.reason: lock-held` and delete nothing.

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
(`SELECT … FOR UPDATE SKIP LOCKED`, held for the run's transaction), so two runs
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
every User, as the nightly one would. The old `APP_DORMANCY_DEACTIVATION_WINDOW`
and `APP_DORMANCY_AUTHORITY_REVOCATION_WINDOW` settings are gone and ignored.

### Required password change

A password change is required of a User — the change-required flag — by every
connector password write (SCIM create, PUT or PATCH carrying `password`), by a
**Force password change** and by an **Unlock** of an account that has a password
(each requiring `user:write`). Only a successful self-service change
(`POST /api/auth/change-password`) clears it; a connector write never does. While
flagged, a session may call `GET /api/auth/me`, the change and
`DELETE /api/auth/logout`, and nothing else — `/api/admin/**` included, whatever
Permissions the User's Groups confer. Any User may change its password at any time through the same
endpoint.

There is no deadline for the change. Instead, a flagged User's logins do not move
its dormancy basis, so a User that keeps logging in with an imposed credential
and never replaces it is locked by the dormancy job once the lockout window has
passed since its creation, last real login, reactivation or Unlock. The completed
change moves the basis.

### Audit trail database roles

The audit table is append-only, and that is a property of the database rather than
of the code writing to it. The `V1` migration creates two roles:

- **`backend_app`** — what every runtime connection assumes, through
  `spring.datasource.hikari.connection-init-sql`. It holds full DML on the tables
  whose lifecycle the application owns (the `scim_*` directory tables and
  `user_counters`) and narrower grants where a table's rule is narrower:
  `SELECT`/`INSERT` only on `scim_tombstones` and `audit_events`, no `UPDATE` on
  `scim_user_password_history`, and `SELECT`/`UPDATE` only on
  `scheduled_job_locks`, so the runtime role cannot remove the row a job
  serializes on. An `UPDATE` or `DELETE` of a recorded event from application code
  is refused by the server. The migration's `GRANT` statements are the exact list.
- **`backend_audit_retention`** — holds `SELECT`/`UPDATE`/`DELETE` on `audit_events` and is
  reserved for the retention job, which assumes it with a transaction-scoped
  `SET LOCAL ROLE` and reverts on commit.

Beside the grants the table carries a `BEFORE UPDATE OR DELETE` trigger that
refuses the statement whatever role issues it, the owning role included, unless
that role is the retention role. Grants say nothing about a connection that arrives
as the owner — a console session, or a deployment that never set the runtime role —
so the trigger is what makes append-only survive a misconfiguration.

Because the runtime role cannot create tables, and does not exist until the
migration that creates it has run, **Flyway connects separately**:
`spring.flyway.user`/`password` default to the same `DATABASE_USERNAME` /
`DATABASE_PASSWORD` credentials, giving migrations a connection outside the pool
that keeps its privileges. Override them if your deployment migrates as a different
role than it serves as. A later migration that adds a table the application writes
must grant `backend_app` on it.

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

Build the container after packaging the application:

```bash
./mvnw clean package
docker build -t backend:local .
```
