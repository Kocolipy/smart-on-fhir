---
type: Decision Record
title: "ADR 0003: ECS-structured logging with redaction"
description: ECS JSON records built only through LogEvent's shapes, a closed event vocabulary, classified ERRORs, SQL text and connection details kept out of every record, and the tests and scans that hold redaction.
tags: [logging, ecs, redaction, observability]
---

# 3. ECS-structured logging with redaction enforced structurally

Date: 2026-09-25

## Status

Accepted.

`LogEvent` is the authority on each operation's `event.action`,
`app.event.action`, level and message, and the call sites on its category and
type. `/backend/docs/LOG_RECORDS.md` is the written copy of that vocabulary and
of every record; this file holds the decisions and their reasons.

## Context

A collector has to be able to query the log, which a pattern layout — every value
text at an offset — does not allow.

More importantly, the account surface
handles values that must not be written to a log store. A `userName` is half a
credential and, on a failed login, very often a mistyped password. A SCIM filter
expression carries whatever attribute values the caller searched on. A password,
a bearer token, a hash and a cookie value are secrets outright. A log store is
read by more people than the database, retained longer, and shipped further, so a
value copied into it has a wider blast radius than the record it came from.

"Do not log those" is a property of every call site at once, which is exactly the
kind of rule review does not hold. The leak that matters is the log line added
next year in a flow no test drives, by someone who has not read this file.

The logging standard (`Structured_Logging_Application_Standard.md`, `Log_Schema.md`
and its recipes) then asked for a fixed envelope, a closed event vocabulary, and a
record for each request, lifecycle event, refusal, session and job run. Those are
the sections below.

## Decision

Each rule is built so it can be checked, not just remembered.

### Format and envelope

**ECS JSON on stdout.** `logging.structured.format.console: ecs`, in its own
`logging.yaml` document imported by `application.yaml`. The test configuration
imports it too: the test resources' `application.yaml` shadows the main one
entirely, so a setting stated only there is one no test can assert. A record's
variable parts are named fields, and a message is a constant.
`LOG_STRUCTURED_FORMAT` set empty restores the human-readable pattern for local
development. The redaction rules hold under any layout.

**Log file.** `logging.file.name: ${LOG_FILE:}` with
`logging.structured.format.file: ecs` and Boot's size-and-time rolling policy (daily
or 50 MB, 14 days, 1 GB total). Unset means no file, which is how local development
and the tests run. The EC2 deployment sets `LOG_FILE=/var/log/backend/backend.json`,
and its CloudWatch agent ships that file into a log group the stack creates with
explicit retention. The service itself never sends a log over the network.
`LogFileTests` starts the configuration in a child JVM, because logging is
JVM-global.

**Service fields.** `logging.structured.ecs.service.*` in `logging.yaml`:

- `name` is stated as `backend`, not inherited from `spring.application.name`.
- `version` comes from the build: `logging.yaml` is the one resource-filtered
  document.
- `environment` comes from `APP_ENVIRONMENT`, default `local`.

**`@timestamp` in UTC+8, the JVM zone untouched.** `EcsTimestampCustomizer`, a
`StructuredLoggingJsonMembersCustomizer`, rewrites the top-level `@timestamp` alone
as `yyyy-MM-dd'T'HH:mm:ss.SSS+08:00` in `Asia/Singapore` (`ServiceTimeZone`). It is
the same instant, so ordering and parsing downstream are unaffected. Setting the
JVM's default zone instead would move SCIM `meta` times, audit times, cron
evaluation and the injected `Clock`. The SCIM wire stays UTC. The cron triggers name
`ServiceTimeZone.ZONE` explicitly, so a job's schedule is evaluated in the zone its
records are read in.

**Trace and span ids.** Each HTTP request (`ServerHttpObservationFilter`) and each
scheduled-job run (an observation opened in `ScheduledJobMetrics.instrumentLocked`)
gets a span from `micrometer-tracing-bridge-otel`. `TraceLogCorrelationConfig`
writes them as the ECS keys `trace.id` and `span.id`, where Boot's defaults would
land as top-level fields no ECS query selects on. These are the only context keys
`LogContext` does not write: they are tracer-minted hex ids, not values anything
else supplies. The tracing is for correlation only:
`management.tracing.export.enabled: false` (`telemetry.yaml`) keeps any exporter
off, and with it Boot installs a no-op propagator. Sampling does not gate the ids.
`TraceExportTests` holds all of it.

### The logging context

**One writer.** `LogContext` exposes named setters and no general-purpose one:

- `requestId` (`http.request.id`);
- `userId(UUID)` (`user.id`), typed so a `userName` cannot be passed;
- `connectorId` (`scim.connector.id`);
- `resourceId` (`scim.resource.id`);
- `job` (`batch.job.name`, `batch.job.run.id`, `trigger.type`).

All of them are ids: the readable identifiers are precisely what must not be
logged. Every value passes through a sanitizer that replaces control characters and
truncates, so a value cannot end the current record and forge the next (CWE-117).
ArchUnit (`mdc_is_only_touched_by_the_log_context`) and Semgrep
(`be-mdc-direct-access`) both hold `LogContext` to being the only production class
that touches `MDC`.

**Correlation ids are minted here, never accepted from the caller.**
`RequestIdFilter` ignores `X-Request-Id` and similar headers, and the no-op
propagator ignores an inbound `traceparent`. Honouring a caller-supplied value
would let a client merge unrelated requests in a log search by repeating one, and
this service sits behind no proxy whose header it has agreed to trust. Trusting
one becomes a deliberate change here when such a contract exists.

**Actor and subject.** `user.id` is always the actor:

- `SessionUserLogContextFilter` sets it for the rest of a request from the session's
  principal index, which holds the SCIM id.
- Where a record concerns a different User (an Unlock, a revocation, a membership
  change of power, a dormancy action), that User is `user.target.id`.
- A SCIM request's actor is `scim.connector.id`, set by the bearer filter.
- Scheduled jobs carry no actor.
- A refused Login carries no user field at all, even on an authenticated session:
  the identity it named is unresolved, and the session's User is not whom it was
  for.

Connector lifecycle records carry the actor only. The connector and token ids stay
with the audit trail.

### The event vocabulary

`LogEvent` declares the `event.kind`, `event.category`, `event.type`, `event.action`
and `event.severity` members this service uses. `LogEvent.Operation` is the single
mapping from what this service does onto the standard's closed action list. Where
the action is shared, or none fits, the precise operation is named under
`app.event.action`, namespaced under the service's own `app.` key rather than
invented into the standard's enum. Semgrep
`be-log-event-action-outside-the-vocabulary` keeps `LogEvent` the only writer of
either key.

`access-control` is kept for access decisions: unlock, access denied and
unauthenticated.

- The lifecycle of the provisioning channel is `user-provisioning`: creating and
  deleting a connector, and revoking its token.
- Issuing and rotating a token grant it Permissions, so they are
  `user-administration`.
- An operation with no local name has an action that no other such operation
  shares (`LogEventTests.everyOperationIsIdentifiableFromItsRecord`), so an
  operation sharing another's action carries a local name beside it.

#### Operations with no event.action

Five operations have no defensible allowed value. Each still carries a stable
`app.event.action`. `LogEventTests.everyOperationHasAnActionOrADocumentedException`
holds this list to the code.

| `app.event.action`     | Why no allowed value fits                                                                                                                                                                                                                 |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `http.request`         | It is the access record for every request, whatever the request did. Any one action would mislabel most requests, and `access-control` would merge every request into the authorization decisions a security search on that value is for. |
| `http.request.refusal` | It records the API refusing malformed input, not an identity operation or an access decision.                                                                                                                                             |
| `http.request.fault`   | It records an unexpected failure, which is not an operation of any kind.                                                                                                                                                                  |
| `audit.retention`      | It deletes aged-out audit rows. No User, session or access is involved.                                                                                                                                                                   |
| `audit.append`         | It records an audit write failing, which is an operational alert rather than an identity operation.                                                                                                                                       |

### Records are built in shapes

`LogEvent` builds the whole record, not just its name. Each shape is a public
method that:

- opens the record at its level;
- classifies it;
- sets `event.outcome` and `event.duration_ms` where the shape has them;
- sets the operation's fixed message for that shape.

The caller names the operation, adds only the ids and counts it alone can supply,
and calls `log()`. So adding one standard field to every record of a shape is a
one-file change.

`/backend/docs/LOG_RECORDS.md`, "Shapes", lists each shape, its level and what it
sets.

**Messages live on the operation.** Each `LogEvent.Operation` member declares, on
its own line, the fixed message of every shape it is written in (`onSuccess`,
`onRefusal`, `onError`, `onSchedule`, and `onChange` for the one operation whose
`change` record needs its own). A scheduled run's summary is its `job-end`, so no
shape writes a summary beside it (`/backend/docs/LOG_RECORDS.md`, "Scheduled jobs"). The shapes with one
message for every operation (`jobStart`, `jobEnd`, `jobFailed`, `requestEnd`, the
outbound records, `jwksRefetchWarning` and `deadlockRetried`) need none. `OperationMessagesTests` reads the
compiled production call sites that hand an operation to a shape, and fails for a
message an operation needs and does not declare, or declares and no call site
needs. A shape given an operation with no message for it still writes the shape's
generic message ("Operation completed") rather than throwing, because a record is
never worth failing the path that writes it.

**Field names live beside their feature.** `LogEvent` keeps the ECS and
`Log_Schema.md` fields, the ones its own shapes write (`app.epic.call`, the cron
and job fields), and the session, login and host fields several modules share. A
key only one feature adds to its records is declared in that feature:
`DormancyLogFields` and `EpicLoginLogFields` in `auth.application`,
`AuditLogFields` in `audit.application`, `ScimLogFields` in `scim.application`, and
`PermissionLogFields` in `authorization.domain`. Each is a list of names only, so the
dependency still runs from the feature to `observability`, and the records are
still built by the shapes above.

Semgrep `be-log-record-outside-log-event` refuses, in production code outside
`LogEvent`:

- a record opened on a logger at any level, `ERROR` included, or through
  `atLevel(...)` or `makeLoggingEventBuilder(...)`;
- the classic `error(...)`, `warn(...)`, `info(...)`, `debug(...)` and `trace(...)`
  calls;
- a write of `event.outcome` or `event.duration_ms`;
- `setMessage(...)`;
- a `log(...)` that passes a message.

An `ERROR` opener is also reported by `be-log-error-without-error-fields`, which
says what such a record would lack. `LogRecordGuardTests` holds the rule to the
code: its message names exactly `LogEvent`'s public shapes, and its patterns cover
every slf4j level. The rule-test fixture `semgrep/tests/service-security.java`
plants records the rule must report, at every level, and `scripts/semgrep.sh` runs
it through `semgrep --test` before the scan.

### Error classification

**Every `ERROR` is classified.** `LogEvent`'s `ERROR` shapes (`error`, `jobFailed`,
`outboundFailed`) are the only ways to write one, and `be-log-error-without-error-fields` refuses any other
`ERROR` record in production code. The fields:

- `error.code` is an HTTP status and only that, `100`–`599` as the Log Schema
  requires: the status a request fault was answered with, or `500` off any request.
  It is never a database's error number or SQLState, which the redacted message
  carries instead (below).
- `error.category` is `database`, `application`, `data` (a caller's refusal, or an
  unusable answer from a service this one called), `network`, `server` or
  `cert/auth`. A failure the database raised takes its category from
  `DatabaseFailureCategory` (below).
- `error.follow_up_action` is `true`, except on the Epic categories that need no
  person (below).

**The `error.*` remap.** Call sites write `error_code`, `error_category` and
`error_follow_up_action` with underscores, because a dotted `error.code` would make
Boot's ECS formatter write a second `error` object beside the one it builds from
the attached throwable. `EcsErrorFieldsCustomizer` replaces both with one `error`
object on the way out:

- `type`, `message` and `stack_trace`, from the same accessors the formatter uses;
- `code`, `category` and `follow_up_action`.

A record with neither an exception nor a classification gets no `error` object.

`/backend/docs/LOG_RECORDS.md`, "Error records", says what each `ERROR` record
attaches.

A **redacted copy** (`RedactedFaultException`) is the original stack under a
message that is only the cause's type name, with no cause chain. It is used where
the message may quote data, as a JDBC driver's quotes the refused row, and it stays
although the SQL exception redaction below would now cover a data-access failure:
a data-access or Hibernate exception with no SQL exception beneath it can quote an
id or a value too, and the copy holds under any layout. A failed scheduled job
attaches its exception whole, because Spring's scheduler error handler logs it whole
regardless and a copy would only cost `error.type` its real class; the SQL exception
redaction is what keeps the database's text out of both records. The audit-append
alert attaches nothing: `OperationalAlerts` carries only the failure's type, so the
alert cannot leak what the event withheld.

**The database category mapping** (ADR 0013, "App-Standards requirements").
`DatabaseFailureCategory` decides the category of a failure the database raised,
keyed on what the engine reports — the vendor error number and SQLState of the
`SQLException` in the cause chain, and Spring's data-access and transaction
hierarchy — and never on a driver's class. The scheduled-job module and the
app-wide handler both classify through it. SQL Server's rows key on the error
number: its driver derives a server error's SQLState from the error's state number,
and the deadlock victim and the snapshot conflict share one (`40001`).

| Failure                                               | What SQL Server reports                                                   | `error.category` | `error.follow_up_action`                 | Level   |
| ----------------------------------------------------- | ------------------------------------------------------------------------- | ---------------- | ---------------------------------------- | ------- |
| Deadlock, first occurrence, retried                   | error `1205` (under `40001`)                                              | `database`       | — (`retry.attempt=1`, `reason=deadlock`) | `WARN`  |
| Deadlock (repeat), snapshot conflict                  | `1205`; `3960`                                                            | `database`       | `true` (investigate)                     | `ERROR` |
| Untranslated unique violation, truncation, conversion | `2627`, `2601`; `2628`; `245`, `8114`                                     | `database`       | `true` (investigate)                     | `ERROR` |
| Lock not acquired or timed out; audit-trigger refusal | `1222`; the driver's timeout, `HY008`; the seed lock not granted; `50001` | `database`       | `true` (investigate)                     | `ERROR` |
| TLS trust failure, refused login, permission denied   | an `SSLException` or `CertificateException` beneath; `18456`; `229`       | `cert/auth`      | `true` (check configuration)             | `ERROR` |

Every other database failure is `database`. Two rows need more than a state:

- **The deadlock retry** (ADR 0013's as-13). A deadlock victim's transaction is
  rolled back whole, so a SCIM or administrative write the database chose is run
  again, once, by `DeadlockRetryAdapter`, an advisor ordered ahead of the
  transaction on every `@RetriedOnDeadlock` method (the SCIM User and Group
  writes, Unlock, setting a Group's Permissions, and connector and token
  administration). The first deadlock is the `deadlockRetried` `WARN`, with no
  `error.code` and no follow-up; a deadlock on the retry marks the request
  (`ExhaustedRetry`) and is written once as the second row's `ERROR`, answered
  `503` with `Retry-After: 1` in the namespace's error document. A deadlock on a
  path not retried stays the generic `500`, so `docs/openapi.yaml` lists the
  `503` on the retried writes alone. A deadlock is error `1205` under `40001`,
  never the snapshot conflict (`3960`) that shares the state.

  **Coverage and placement are rules** (`ArchitectureTest`). Every public method
  that opens a transaction which is not read-only, whether by `@Transactional` or
  by hand through a `TransactionTemplate`, carries the retry unless
  `NOT_RETRIED_ON_DEADLOCK` names it with its reason. A retried method opens a
  read-write transaction of its own and is called only by an inbound web adapter,
  since a write joining an open transaction could not be re-run. A fail-open
  audit event (`Audited.Refused`, ADR 0004) ends the retried body that appends
  it, since it commits at once and a retry would append it twice.

- **The audit trigger's refusal** (ADR 0004, "The database keeps the trail
  append-only"). The append-only
  trigger throws its own error, `50001`, so it is `database` and can never be
  mistaken for the permission refusal (`229`) a missing grant raises. What reaches
  the trigger is a principal the grants let through — the owner login — and the app
  login is refused by its grants first.

- **The seeding lock not granted.** The seeding lock is a row lock (ADR 0013,
  "Collation and the seed lock") bounded by a 60 s query timeout; a wait that runs out fails
  the start as Spring's `CannotAcquireLockException`, a `DataAccessException`, and
  so `database`.

`DatabaseFailureCategoryTests` holds every row; `DatabaseFailureLogIntegrationTests`
drives the ones SQL Server can produce and asserts each record.
`ScimDeadlockRetryIntegrationTests` forces real deadlocks, and the contract
fixtures produce every documented `503` through `ForcedDeadlock` — SQL Server lets
no statement raise `1205`, so it stages a real deadlock against a partner
connection.

**No double `ERROR`.** A handler that writes a request's fault `ERROR` marks the
request (`RequestFault`), and `RequestIdFilter` then writes that request's record at
`WARN`, unclassified: still the `5xx` and still `failure`, but not the same failure
twice. An Epic call that got no answer is logged at `ERROR` once, by the outbound
interceptor, not again when the Login ends for it.

### SQL exception text

**A database's error text never reaches a record.** It quotes what it refused —
the duplicate key, the truncated or unconvertible value, the failing row — and
names the schema, the table and sometimes the database. The exceptions that wrap it
repeat it: Spring's translated `DataAccessException` carries the driver's message
and the statement, Hibernate's the same. So `EcsErrorFieldsCustomizer`, which every
structured record with an attached exception passes through whoever logged it,
writes `error.message` and `error.stack_trace` from a redacted copy of the
exception tree (`SqlExceptionRedaction`):

- the message of every `SQLException` in the tree, causes and suppressed ones
  included, becomes `SQL Server error <n>`, the server's error number (`0` for a
  failure the driver raised itself), with no SQLState;
- the message of every throwable with an `SQLException` beneath it in its cause
  chain becomes that one's redacted message, because it quotes it;
- a cause beneath the `SQLException` (the socket failure a driver reports) keeps
  its message, and every class name and frame is kept.

The rule is keyed on the generic `SQLException`, never a driver's class, and the
message names only the error number. It holds in
the structured layouts, console and file, which every deployment runs. The pattern layout a developer may opt into
locally (`LOG_STRUCTURED_FORMAT` empty) prints an attached exception as the JVM
does; the call sites that attach a redacted copy (above) are what stays redacted
there. `EcsErrorFieldsCustomizerTests` holds the rule under both of the encoder's
renderers, and `DatabaseFailureLogIntegrationTests` holds it for real SQL Server
refusals whose statements name their tables by database and schema: no emitted
field carries the refused value, the database name or the schema name.

### Connection details

**No log line names the database's location.** A JDBC URL names the host, the port
and the database. `log-levels.yaml` pins each library logger that writes one, with a
comment on each saying what it would write:

- `org.flywaydb.core.FlywayExecutor` at `WARN` (Flyway's database-information line);
- `org.hibernate.orm.connections.pooling` at `WARN` (Hibernate's `HHH10001005`
  database-information block, whose default catalog is the database name);
- `com.zaxxer.hikari` at `INFO`, so Hikari logs only pool names — `app`,
  `audit-append` and `retention`, the service's three pools (at `DEBUG` its
  configuration dump, written as each pool opens, includes `jdbcUrl`);
- `com.microsoft.sqlserver.jdbc` at `WARN` (the driver's connection trace).

A level stated for the logger itself is one a broader setting cannot reach, so
turning logging up to investigate does not turn these lines back on. The levels hold
for the migrate step (`MigrationStep`) as for the service (ADR 0013, ac-1).
`StartupLogConnectionDetailsTests` runs the migrate step and the service in child
JVMs, as a deployment does, also with the root logger at `DEBUG` and all three
pools opened, and asserts no line names the JDBC URL, the host or the database.

### The records

`/backend/docs/LOG_RECORDS.md` lists every record and the fields it carries. The
rules behind them:

- **One record per request, at its end.** `RequestIdFilter` writes it inside
  Boot's observation filter, so it carries `trace.id`, and ahead of the security
  chain, so a request the chain refuses is recorded too. The raw path, query
  string, headers, cookies, body and client address are never read into it.
- **Startup states the effective non-secret settings that change behaviour**,
  and no datasource, Redis, credential or identity setting. The session timeouts
  and the lockout threshold are deliberately absent: the standard (§2 #0) forbids
  logging timeout or retry values for authentication flows.
- **A refusal names no role, matcher, Permission or authority**, and no part of a
  presented token, not even a prefix or the lookup id. Permissions are named in
  two records only: a membership change of power (what the Group confers), and a
  dormancy Membership revocation (what the User lost).
- **A refused Login carries no reason and no user field**, so a locked or
  deactivated User's refusal reads exactly as a wrong password's. The reason goes
  to the audit trail and the `login` counter's `reason` tag only (ADR 0012, "the
  account reasons are audit-only"). `session.hash`, the first 64 bits of the
  SHA-256 of a session id, correlates it beyond `trace.id`: a session id is a
  random UUID, so its hash cannot be guessed back the way a password's can. No
  record carries the session id or the cookie value.
- **A session that idles out is not observed.** Observing it needs Redis keyspace
  notifications, which `session.yaml` leaves off because ElastiCache disables
  `CONFIG`.
- **A dormancy lockout is `WARN`**, not the `ERROR` the authentication recipe gives
  a lockout, because it signals inactivity rather than an attack.
- **A scheduled run's summary is its `job-end`**, carrying what the run committed,
  so a failed run still says how far it got. `trigger.type` is `scheduled`, the
  schema's own value, not `cron`.
- **`event.duration_ms`, not ECS's `event.duration`.** Every record states its
  duration in milliseconds under that name; ECS's own field is in nanoseconds, so
  a second spelling would split every duration query in two.
- **A closed SCIM namespace writes no refusal record** for its `404`: it should
  look like no namespace at all. `log-levels.yaml` turns
  `DefaultHandlerExceptionResolver` and Hibernate's JDBC error logger `OFF`,
  because both write unstructured records that quote what the caller or the
  driver sent.

### Redaction gates

The test can only speak for the flows it drives, and the scan speaks for call sites
that do not exist yet. Neither alone is the property.

- **Tests.** Each drives a flow through the real stack, encodes every record with
  the production encoder, and asserts no fixture credential or identifier appears
  anywhere in the bytes: `EcsLogFormatTests` (logins and an administrative change),
  `JdbcErrorLogRedactionTests` and `DatabaseFailureLogIntegrationTests` (driver and
  database errors), `StartupLogConnectionDetailsTests` (start-up),
  `EpicLoginRedactionIntegrationTests` and `EpicDiscoveryIntegrationTests` (every
  Epic Login outcome, against ADR 0012's D22), and
  `PatientErrorContractIntegrationTests` (patient reads).

- **Scans.** `be-log-message-concatenation` refuses a value concatenated into a
  message. `be-log-sensitive-value` refuses a value whose name says it is an
  identifier or a secret being passed to a logging call, including as a `{}`
  parameter. Its names come in two families:
  - Names matched anywhere in the argument: password, token (which covers
    `id_token`, `access_token` and `refresh_token`), hash, and similar — and a
    Patient's `nric` (but not the tail of `enrich`), a patient being logged by its
    id alone (`docs/domain/patient.md`).
  - D22's other names (`code`, `authorizationCode`, `state`, `nonce`, `launch`, the
    verifier, the assertion, `privateKey`, `pem`, `clientKey`, `clientNextKey`, and
    the two key variables), matched as a whole identifier only. Otherwise
    `statusCode` or `stateRule` would be refused.

  The signing keys' `kid`s are deliberately outside both families: a `kid` is
  public, and the startup record logs both. `EpicTokenSet.toString()` names each
  token by its presence alone. One call site is suppressed, with its reason, in
  `LoginOutcomeService`: the failed-call `ERROR` passes `failed.code()`, an HTTP
  status, not anything Epic sent.

A client-influenced value that genuinely must be recorded, such as a PATCH attribute
path or a `scimType`, is emitted as a sanitized field, never concatenated.

## Consequences

- Local development output is JSON by default.
- An investigation starts from the request record, which names the caller by id.
  The audit trail, not this stream, is what authoritatively records who changed
  what.
- Adding an operation means a `LogEvent.Operation` member with its action, or an
  entry under "Operations with no event.action", and `LogEventTests` fails until one
  exists. The same line declares its messages, which `OperationMessagesTests` holds
  to its call sites. Its feature's own field names go in that feature, not in
  `LogEvent`.
- A new value that must never be logged needs its name in `be-log-sensitive-value`
  if the name does not already match, and a redaction test that drives the path
  handling it.
- A request that never ends, such as a hung thread, has no record. The thread dump
  and `http.server.requests`' active-request gauge are what find it.

## Alternatives considered

**A request-start record.** The standard says to log a request's start. The end
record carries everything a start record would, plus the status, duration and
outcome only the end knows. A start record would double the stream for no field an
investigation lacks. One at `DEBUG` would meet the letter of the rule while being
off in every deployment. Not written. The end record is treated as meeting the
rule.

**The request record inside the security chain**, where the actor's scope is open.
Rejected: a request the chain refuses before any inner filter runs would lose its
record, which is the record most worth keeping. The actor is marked on the request
instead.

**`session-start` on container session creation**, as the recipe does with
`HttpSessionCreatedEvent`. Rejected: the only session created before authentication
is the anonymous one that holds the CSRF token. It either becomes the signed-in
session (renamed, not recreated) or idles out. Recording its creation would add a
record per page load that names no one, and leave a `session-start` with no
`session-end` for every abandoned one. Writing at the login also lets the record
carry `user.id`.

**A formatter of our own** for the timestamp or the error object. Rejected: it would
be a copy of Boot's that drifts from it. Two `StructuredLoggingJsonMembersCustomizer`s
adjust Boot's output instead.

**Setting the JVM default zone to `Asia/Singapore`.** Rejected: it moves every
time the service handles, not just the log's.
