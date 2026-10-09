# 3. ECS-structured logging with redaction enforced structurally

Date: 2026-09-25

## Status

Accepted.

The addenda below are dated records and later ones supersede parts of earlier
ones, so read the current state from the code, not from the first table that
matches. `LogEvent` (`Operation`, and the shapes that build every record) is the
authority on each operation's `event.action`, `app.event.action`, category, type,
level and message; "The full mapping" in the 2026-10-02 addendum is its latest
written copy, and the job rows of the #67 and #70 tables were retired by the
dormancy addendum.

## Context

The service had no logging configuration and no application log statements at all:
whatever Boot's default pattern layout printed was the whole log stream. Two
things are wanted of it before the SCIM surface lands.

First, a collector has to be able to read it. The pattern layout is a sentence,
so every value in a record is text at an offset, and a reader either writes a
regular expression per message or gives up on querying.

Second, and the reason this is not a formatting preference: the account surface
handles values that must not be written to a log store. A `userName` is half a
credential and, on a failed login, very often a mistyped password. A SCIM filter
expression carries whatever attribute values the caller searched on. A password,
a bearer token, a hash and a cookie value are secrets outright. A log store is
read by more people than the database, retained longer, and shipped further, so a
value copied into it has a wider blast radius than the record it came from.

"Do not log those" is a property of every call site at once, which is exactly the
kind of rule review does not hold. The leak that matters is the log line added
next year in a flow no test drives, by someone who has not read this file.

## Decision

Three parts, each chosen so the rule is checkable rather than remembered.

**ECS JSON on stdout.** `logging.structured.format.console: ecs`, in its own
`logging.yaml` document imported by `application.yaml` — the same arrangement
`session.yaml` uses, and for the same reason: the test resources'
`application.yaml` shadows the main one entirely, so a setting stated only there
is a setting no test can assert. A record's variable parts are therefore named
fields, and a message is a constant.

**One writer for the logging context.** `LogContext` exposes named setters
— `http.request.id`, `scim.connector.id`, `scim.resource.id`, and since #67
`user.id` (see the addendum) — and no general-purpose one. All of them are ids: the readable identifiers are precisely
what must not be logged. Every value passes through a sanitizer that replaces
control characters and truncates, so a value that reached a connector id from a
token cannot end the current record and forge the next (CWE-117). `ArchUnit`
(`mdc_is_only_touched_by_the_log_context`) and Semgrep (`be-mdc-direct-access`)
both hold `LogContext` to being the only production class that touches `MDC`.

**Correlation ids minted here, never accepted from the caller.**
`RequestIdFilter` runs ahead of the security chain, so a request refused before it
reaches a handler is still logged under an id, and it stores the id on the request
so the container's error dispatch reports the same exchange rather than a second
one. It ignores `X-Request-Id` and friends: honouring a caller-supplied value
would let a client merge unrelated requests in a log search by repeating one, and
this service sits behind no proxy whose header it has agreed to trust. When such a
contract exists, trusting it becomes a deliberate change here.

The gates are two, deliberately overlapping:

- `EcsLogFormatTests` drives an accepted login, a refused login and an
  administrative change through the real filter chain, encodes every resulting
  record with the production encoder, parses each as ECS JSON, and asserts that no
  fixture credential or identifier appears anywhere in the encoded bytes.
- `be-log-message-concatenation` and `be-log-sensitive-value` reject, at scan
  time, a value concatenated into a message and a value whose name says it is an
  identifier or a secret being passed to a logging call — including as a `{}`
  parameter, which is the idiomatic form of this leak and the one a formatting
  rule would miss.

The test can only speak for the flows it drives; the scan speaks for call sites
that do not exist yet. Neither alone is the property.

## Consequences

Local development output is JSON by default. `LOG_STRUCTURED_FORMAT` set empty
restores the human-readable pattern; the redaction rule does not depend on the
format either way, since nothing is permitted to log those values under any
layout.

The records this change adds name no subject. `LoginService` logs the outcome of
an attempt and the type of a refusal; `AccountAdministrationService` logs which
administrative action was applied or refused. Neither names the account, because
an account is currently identified by `username` alone and there is nothing else
to name it by. That is a real gap — "an administrative change happened" without
"to whom" — and it closes when the account aggregate gains a stable id: the id
goes in the logging context as `scim.resource.id`, and the audit trail, not this
stream, becomes what authoritatively records who changed what.

A structured field remains available for a client-influenced value that genuinely
must be recorded — a PATCH attribute path, a `scimType` — provided it is emitted
as a field rather than concatenated, and sanitized on the way in.

## Addendum (2026-10-01): `user.id` and the standard event vocabulary

Issue #67. The stable id the Consequences above waited for now exists — the SCIM
User resource id — and the logging standard (`Log_Schema.md` §User, §Event) asks
for it and for a closed event vocabulary.

**`user.id`.** `LogContext` gains a fourth setter, `userId(UUID)`, typed so that a
`userName` cannot be passed. It is set for the rest of a request by
`SessionUserLogContextFilter`, placed after `SecurityContextHolderFilter`, from
the session's principal index — which the login writes with the SCIM id, not the
`Authentication`'s name. It is set explicitly on the login-success record, because
that index is written only after the record is emitted. A refused login carries
no `user.*` field at all, even when the request arrived on an authenticated
session: the identity the attempt named is unresolved, and the session's User is
not whom it was for (User standard §3.4).

**Actor and subject.** `user.id` is always the actor. Where a record concerns a
different User — admin unlock and force-change, applied or refused — the User acted
on is `user.target.id`, following ECS's `user.target.*`. Connector lifecycle
records carry the actor only: their subject is a connector, not a User, and the
connector and token ids stay with the audit trail as before. The scheduled jobs
run with no actor and carry no user field.

**The vocabulary.** `LogEvent` declares the `event.kind`, `event.category`,
`event.type`, `event.action` and `event.severity` members this service uses, plus
`event.duration_ms` (which replaces `audit.retention.duration_ms`). A record is
classified by `LogEvent.classify(record, Operation, Category, Type...)`, and
`LogEvent.Operation` is the single mapping from what this service does onto the
standard's action. Where no action fits, or one action covers several operations,
the operation's own name is kept under `app.event.action` — namespaced under the
service's own `app.` key — rather than a member being invented for the standard's
enum. Semgrep `be-log-event-action-outside-the-vocabulary` holds `classify` to
being the only writer of either key.

| Operation (old `event.action`)                               | `event.action`                | `app.event.action`                      | `event.category` | `event.type`                          |
| ------------------------------------------------------------ | ----------------------------- | --------------------------------------- | ---------------- | ------------------------------------- |
| `login`, accepted                                            | `user-authentication`         | —                                       | `process`        | `user`, `allowed`                     |
| `login`, refused                                             | `user-authentication`         | —                                       | `process`        | `user`, `denied`                      |
| `identity.unlock`, applied / refused                         | `access-control`              | `identity.unlock`                       | `process`        | `admin`, `user`, `change`/`denied`    |
| `identity.force_password_change`, applied / refused          | `password-change-enforcement` | —                                       | `process`        | `admin`, `user`, `change`/`denied`    |
| `identity.password_change` (self-service), applied / refused | `user-administration`         | `identity.password_change`              | `process`        | `user`, `change`/`denied`             |
| `scim.connector.create`                                      | `access-control`              | `scim.connector.create`                 | `configuration`  | `admin`, `creation`                   |
| `scim.connector.delete`                                      | `access-control`              | `scim.connector.delete`                 | `configuration`  | `admin`, `deletion`                   |
| `scim.connector.token.issue`                                 | `access-control`              | `scim.connector.token.issue`            | `configuration`  | `admin`, `creation`                   |
| `scim.connector.token.rotate`                                | `access-control`              | `scim.connector.token.rotate`           | `configuration`  | `admin`, `change`                     |
| `scim.connector.token.revoke`                                | `access-control`              | `scim.connector.token.revoke`           | `configuration`  | `admin`, `deletion`                   |
| `scim.write` (integrity violation)                           | `user-provisioning`           | `scim.write`                            | `database`       | `error`                               |
| `identity.inactivity_deactivation`, run                      | `user-administration`         | `identity.inactivity_deactivation`      | `batch`          | `job-end`                             |
| `identity.dormant_authority_revocation`, run                 | `access-control`              | `identity.dormant_authority_revocation` | `batch`          | `job-end`                             |
| `audit.retention`, run                                       | — (no action fits)            | `audit.retention`                       | `batch`          | `job-end`                             |
| any job's schedule at startup                                | as the job's row              | as the job's row                        | `configuration`  | `info`                                |
| `audit.append` (append failed)                               | — (no action fits)            | `audit.append`                          | `database`       | `error`, plus `event.severity` `high` |

`event.kind` is `event` on every record. Two operations have no standard action:
the enum offers nothing for deleting aged-out audit rows or for an audit write
failing, and `access-control` or `user-administration` would mislabel them for a
search on those values, so they carry `app.event.action` alone. The job records
are `job-end` only: a run emits one record, at its end, carrying
`event.duration_ms` where it measures one; `job-start` records arrive with #70,
which emits the scheduled jobs' start/end pair using this vocabulary.

## Addendum (2026-10-01): the record envelope — trace ids, service fields, UTC+8, log file

Issue #66. The logging standard (`Structured_Logging_Application_Standard.md` §3.1,
§3.5, §4, §6) requires fields on every record that the Decision above does not
produce, and a durable local file for a forwarding agent.

**Trace and span ids.** `micrometer-tracing-bridge-otel`, wired by Boot's
`spring-boot-micrometer-tracing-opentelemetry` module, gives each observation a
span: every HTTP request (Boot's `ServerHttpObservationFilter`) and, through an
observation opened in `ScheduledJobMetrics.instrumentLocked`, every scheduled-job run.
Every scheduled job now runs through `instrumentLocked`, so every job record is correlated.
`TraceLogCorrelationConfig` replaces Boot's `Slf4JEventListener` with one writing
the ECS keys `trace.id` and `span.id` — the defaults (`traceId`, `spanId`) would
land as top-level fields no ECS query selects on. These are the first context keys
`LogContext` does not write. That is acceptable for the reason the class exists:
the values are tracer-minted hex ids, not values anything else supplies.

The tracing is for correlation only. No exporter is on the classpath, and
`management.tracing.export.enabled: false` (`telemetry.yaml`) keeps it that way if
one arrives transitively. The same switch makes Boot install a no-op propagator, so
an inbound `traceparent` is ignored and every trace id is minted here — the same
rule the Decision applies to `X-Request-Id`, for the same reason. Sampling does not
gate the ids: an unsampled span still has them. `TraceExportTests` holds all of it.

**Service fields.** `logging.structured.ecs.service.*` in `logging.yaml`: `name`
stated as `backend` (not inherited from `spring.application.name`), `version`
from the build — `logging.yaml` is the one resource-filtered document, so
`@project.version@` becomes the artefact's version — and `environment` from
`APP_ENVIRONMENT`, default `local`. The test configuration now imports
`logging.yaml` as well, so tests record what a deployment records.

**`@timestamp` in UTC+8, the JVM zone untouched.** Boot's ECS formatter writes the
event's `Instant` as UTC and takes no zone. Rather than a formatter of our own — a
copy of Boot's that would drift from it — `EcsTimestampCustomizer`, a
`StructuredLoggingJsonMembersCustomizer` registered through
`logging.structured.json.customizer`, rewrites the top-level `@timestamp` member
alone as `yyyy-MM-dd'T'HH:mm:ss.SSS+08:00` in `Asia/Singapore`
(`ServiceTimeZone`). It is the same instant, so ordering and parsing downstream
are unaffected. The JVM's default zone is deliberately not set: that would move
SCIM `meta` times, audit times, cron evaluation and the injected `Clock`, and the
SCIM wire stays UTC. The cron triggers instead name `ServiceTimeZone.ZONE`
explicitly, so a job's schedule is evaluated in the zone its records are read in.

**Log file.** `logging.file.name: ${LOG_FILE:}` with `logging.structured.format.file:
ecs` and Boot's size-and-time rolling policy (daily or 50 MB, 14 days, 1 GB total).
Unset means no file, which is what local development and the tests run with. The
EC2 deployment sets `LOG_FILE=/var/log/backend/backend.json` and its CloudWatch
agent ships that file into a log group the stack creates with explicit retention;
the service itself never sends a log over the network. `LogFileTests` starts the
configuration in a child JVM, because logging is JVM-global.

## Addendum (2026-10-01): request records and lifecycle records

Issue #68. The standard (`Structured_Logging_Application_Standard.md` §2 #0 and #1,
§3.1, §3.4) asks for a record per request and for startup and shutdown records; the
service wrote neither.

**One record per request, at its end.** `RequestIdFilter` writes it from the
`finally` of the request dispatch, so a refusal by the security chain and an
exception escaping a handler get one as surely as a success; the error dispatch,
the same exchange's second pass, writes none. It carries `http.request.method`
(from a fixed set of methods, `_OTHER` beyond it, because a client chooses the
method), `http.route`, `http.response.status_code`, `event.duration_ms` and
`event.outcome` (`success` below 400). The level follows the status: `INFO`
below 400, `WARN` for a 4xx, `ERROR` for a 5xx. An exception escaping the chain
is recorded as the `500` the container then answers.

`http.route` is the template Spring's handler mapping matched
(`/scim/v2/Users/{id}`), or `unmatched` when none did — which is every refusal
the security chain makes before dispatch. Neither ECS nor `Log_Schema.md` has a
template field, so the name is OpenTelemetry's. The raw path, the query string,
headers, cookies, the body and the client address are never read into the record
at all, so an id or a filter expression in the URL has no way into it.
`/actuator/health` (and its probe sub-paths) and `/actuator/prometheus` get no
record: they are infrastructure polling every few seconds, and their status and
timing are already on `http.server.requests`.

**No start record.** The standard says to log a request's start. The end record
carries everything a start record would — method and route — plus the status,
duration and outcome only the end can know, so a start record would add no field
an investigation lacks while doubling the stream. One at `DEBUG` would satisfy
the letter of the rule while being off in every deployment (§3.3: DEBUG is not
for permanent production use), so this service writes none and treats the end
record as satisfying "log the request's start". A request that never ends — a
hung thread — is the case this gives up, and the thread dump and
`http.server.requests`' active-request gauge are what find that.

**Filter order.** For the request record to carry the `trace.id` every other
record of its request carries, it must be written inside the request's span, so
`RequestIdFilter` now runs at `HIGHEST_PRECEDENCE + 2`, immediately inside Boot's
`ServerHttpObservationFilter` (`+ 1`) rather than ahead of it. It is still far
ahead of the security chain, which is what the Decision above relies on.
`EcsLogFormatTests` reads the three registrations' orders.

**Startup and shutdown.** `ApplicationLifecycleLog` (in its own `lifecycle`
slice, because it reads the SCIM, auth and audit slices' settings and each of
those depends on `observability`) writes:

- on `ApplicationReadyEvent`, `application-startup` with `host.name`, `host.ip`,
  `spring.profiles.active`, and the effective value of each non-secret setting
  that changes behaviour: `app.scim.enabled`,
  `app.dormancy.deactivation.window`, `app.dormancy.authority_revocation.window`,
  `app.audit.retention.period`. Effective rather than configured: a window whose
  default belongs to a domain policy is read from that policy, so an unset
  setting shows the default it resolved to. `service.*` comes from the formatter,
  as on every record. No datasource, Redis, credential or identity setting is
  read at all.
- on `ContextClosedEvent`, `application-shutdown` with the context's uptime as
  `event.duration_ms`.

Each answers only for its own context, so a management child context closing does
not log a second shutdown. A host that cannot resolve its own name gets no
`host.*` fields rather than a placeholder.

The session timeouts and the lockout threshold are deliberately absent, although
the ticket asked for them: §2 #0 of the standard says not to log "timeout or retry
values for authentication flows", and they are exactly that. The standard wins.
An operator checking them after a deploy reads them from the deployment's
configuration (`APP_SESSION_ABSOLUTE_LIFETIME`, `APP_LOCKOUT_MAX_ATTEMPTS`,
`server.servlet.session.timeout`), not from the log. The lifecycle tests assert
the keys stay off the record.

| Operation                   | `event.action`         | `app.event.action` | `event.category` | `event.type`    |
| --------------------------- | ---------------------- | ------------------ | ---------------- | --------------- |
| inbound HTTP request        | — (no action fits)     | `http.request`     | `network`        | `access`, `end` |
| application ready           | `application-startup`  | —                  | `process`        | `start`         |
| application context closing | `application-shutdown` | —                  | `process`        | `end`           |

Both lifecycle records carry `event.outcome` `success` and `event.severity` `low`,
as the standard's lifecycle recipe has them.

## Addendum (2026-10-01): refusals, logout and session end

Issue #69. Authorization and authentication refusals, logout, the end of a session and every
SCIM business refusal reached the caller with no record of their own
(`Structured_Logging_Application_Standard.md` §2 #2, Failure Paths #1, §3.4; User standard
§3.4).

**401 and 403.** Both chains record through `AccessRefusalLog`: one `WARN` record carrying
`event.reason` from a closed set, `http.request.method`, `http.route`, the status, and the
caller from the logging context (`user.id`, or `scim.connector.id` — the bearer filter now
puts the connector's id in the context for the rest of an authenticated SCIM request). The
reasons are `no-session` and `session-expired` (application chain, `401`), `bearer-missing`
and `bearer-invalid` (SCIM, `401`), `insufficient-permissions` (any authorization rule —
a missing Permission, an undeclared route, the forced-password-change confinement; named
`access-denied` until ADR 0010's Permissions replaced roles) and `csrf` (a missing or invalid
token — recorded apart from an authorization refusal). A connector token lacking the
Permission a SCIM request needs is `insufficient-permissions` too, the same reason a session
gets; it was `insufficient-scope` while tokens had a read-only/read-write scope (#117).
No reason names a role, a matcher or an authority, and no part of a presented token — not a
prefix, not the lookup id — reaches a record.

The route is the template the request would have matched. The chain refuses before the
dispatcher runs, so `RouteTemplates` asks the application's `requestMappingHandlerMapping`
which of its mappings match, without dispatching and without leaving the match on the request:
the #68 request record still files a refused request under `unmatched`.

A refusal is recorded once per exchange: only on the request dispatch, and only the first time
(`AccessRefusalLog` marks the request). The request record is a separate record and is not a
second refusal record; `RefusalLogIntegrationTests` checks the count over a real socket, where
the container's error dispatch exists.

**Logout and session end.** A logout of a signed-in session writes `user-logout` at `INFO`
with `user.id`, after the audit append it accompanies. A session ended by its absolute lifetime
writes `session-end` with `event.reason` `absolute-lifetime` and the session's own `user.id`,
read from its principal index before it is invalidated. A revocation through `AccountSessions`
that ended at least one session writes `session-end` with `event.reason` `revoked`
(`revokeAll`) or `replaced-by-login` (`revokeAllExcept`), the account as `user.target.id`,
the actor left as the request carries it, and `session.ended_count` — never a session id, which
is the session's bearer credential. A session that simply idles out in Redis is not observed by
the service and gets no record.

**SCIM refusals.** `ScimExceptionHandler` writes one record per refusal: `WARN` for a 4xx,
`ERROR` with the exception attached for a 5xx. `event.reason` is the `scimType`, or where
SCIM defines none the refusal's own name (`notFound`, `preconditionFailed`,
`unsupportedQuery`, `payloadTooLarge`, `notImplemented`, `serverError`) —
`ScimErrorException.reason()`. The record adds `scim.resource.type` (`User`/`Group`, from the
matched route) and `scim.resource.id` (the route's `{id}`, only when it is a UUID), and never
the detail or any submitted value. The unmapped-integrity-violation path keeps its own
`scim.write` record as the single `ERROR`; its exception is attached as a redacted copy — the
original stack under a message that is only the cause's type, with no cause chain — because
the driver's message quotes the refused row. Refusals made by `ScimRequestBodyLimitFilter` (a
declared body over the bound) and `ScimDispatcherErrorFilter` (the dispatcher's own `404`,
`405`, `406`, `415`) before any handler runs get the same `scim.refusal` record, written by
`ScimErrorDocument` as it writes the error document: `event.reason` is fixed by the status
(`notFound`, `methodNotAllowed`, `notAcceptable`, `payloadTooLarge`, `unsupportedMediaType`;
`serverError` for a 5xx, at `ERROR`; `requestRefused` for any other 4xx) and spelled as the
handler spells the same refusal, and there is no `scim.resource.type`, since no handler was
matched. The release gate's `404` while the namespace is closed is deliberately not a refusal
record: the closed namespace is meant to look like no namespace at all. Spring MVC's
`DefaultHandlerExceptionResolver` would otherwise add an unstructured `WARN` of its own for
those same dispatcher refusals, quoting the method, `Content-Type` or `Accept` the caller sent,
so `log-levels.yaml` turns that category `OFF`, as it does Hibernate's JDBC error logger.

| Operation               | `event.action`      | `app.event.action`       | `event.category` | `event.type`       |
| ----------------------- | ------------------- | ------------------------ | ---------------- | ------------------ |
| request refused, `403`  | `access-control`    | `access.denied`          | `process`        | `access`, `denied` |
| request refused, `401`  | `access-control`    | `access.unauthenticated` | `process`        | `access`, `denied` |
| logout                  | `user-logout`       | —                        | `process`        | `user`, `end`      |
| session ended           | `session-end`       | —                        | `process`        | `end`              |
| SCIM refusal, 4xx / 5xx | `user-provisioning` | `scim.refusal`           | `process`        | `denied` / `error` |

## Addendum (2026-10-02): scheduled job start, end, failure and run identity

Issue #70. The three scheduled jobs logged one completion record each, with no start, no
job identity and nothing of their own when a run failed (`Structured_Logging_Application_Standard.md`
§2 #5, §3.1, §4; `Log_Schema.md` §Trigger, §Batch, §Error). This supersedes the three job
"run" rows of the #67 table above and the sentence beneath it saying job records are
`job-end` only.

**One wrapper owns it.** Every job already ran through `ScheduledJobMetrics`, so its job
logging lives there and no job re-implements it. `instrumentLocked(job, operation, task)` wraps a
job that serializes on its lock row (ADR 0005) and returns a `SkippableJobRun` — the dormancy
jobs' `DormancyRun`, the retention job's `AuditRetentionRun` (#97, which retired the lockless
`instrument`) — because only the job, which takes the lock inside its own
transaction, knows whether it ran. Each run, inside the observation that gives it its trace:

- puts `batch.job.name` (the metric's `job` tag, so one name selects a job's metrics and
  records alike), `batch.job.run.id` (a fresh UUID) and `trigger.type` `scheduled` in the
  logging context through `LogContext.job`, the fifth setter;
- writes `job-start`;
- writes `job-end` at `INFO` with `event.outcome` `success` and `event.duration_ms` — with
  `event.reason` `lock-held` when the job reports it skipped, and then no other record of
  the job's;
- or, when the job throws, writes `job-end` at `ERROR` with `event.outcome` `failure`,
  `event.severity` `high`, `event.duration_ms`, `error_code` `500`, `error_category`
  (`database` for Spring's data-access and transaction exceptions, `application` otherwise)
  and `error_follow_up_action` `true`, the exception attached (so `error.type` is its
  class), and rethrows — the failure counter and the scheduler's handling are unchanged;
- closes the context scope however it ended, so no `batch.*`/`trigger.*` key is left on the
  scheduler's thread.

Both ends are classified as the job's own `Operation`, so a search on a job's action finds
its start, its end and what it did between them. The run id sits beside the trace id rather
than replacing it: `trace.id` stays the tracer's (#66), and the standard asks only that a
run's records share both. `trigger.type` is an array in the schema; the logging context holds
strings, so it is the one member. The issue asked for `trigger.type=cron`; the schema's enum
is `scheduled`, `job-dependency`, `ad-hoc`, so it is `scheduled`.

**The error fields are spelled with underscores** — `error_code`, `error_category`,
`error_follow_up_action` — as `Log_Schema.md` and its recipes prescribe: Boot's ECS formatter
owns the `error` object it writes from the attached throwable, and a dotted key of ours in it
would collide. They are not remapped into `error.*`.

**The exception is attached whole**, unlike the redacted copy the SCIM integrity-violation
record attaches. The same exception goes on to Spring's scheduler error handler
(`TaskUtils$LoggingErrorHandler`), which logs it whole regardless, so a redacted copy here
would hide nothing and would cost `error.type` its real class.

**The jobs' own records** keep what only the job knows — rows deleted, Users deactivated,
memberships revoked, and the window — and inherit the run's context keys. They are the run's
summary rather than its end, so they are `event.type` `info`; their `event.outcome` and the
retention job's `event.duration_ms` moved to `job-end`, and `dormancy.skipped` is gone (a
skipped run's record is the `lock-held` `job-end`).

**The startup schedule records** state `batch.job.name`, `app.job.description` (what the job
does, in a sentence — the schema has no field for it, so it is namespaced under `app.` as
`app.event.action` is), `trigger.cron.expression` and `trigger.cron.timezone`
(`Asia/Singapore`, `ServiceTimeZone.ZONE`, the zone #66 evaluates the crons and writes the
timestamps in). They replace `audit.retention.schedule` and `dormancy.schedule`.
`ScheduledJobMetrics.scheduled` builds them, so the configuration classes only add the job's
window and log.

| Operation                                        | `event.action`        | `app.event.action`                      | `event.category` | `event.type`            |
| ------------------------------------------------ | --------------------- | --------------------------------------- | ---------------- | ----------------------- |
| any job, run start / end                         | as the job's row      | as the job's row                        | `batch`          | `job-start` / `job-end` |
| `identity.inactivity_deactivation`, summary      | `user-administration` | `identity.inactivity_deactivation`      | `batch`          | `info`                  |
| `identity.dormant_authority_revocation`, summary | `access-control`      | `identity.dormant_authority_revocation` | `batch`          | `info`                  |
| `audit.retention`, summary                       | —                     | `audit.retention`                       | `batch`          | `info`                  |

## Addendum (2026-10-02): the app-wide error handler, and `error.*` on every ERROR

Issue #94 (audit findings LOG-6, LOG-7, LOG-N3). An unexpected exception outside SCIM was never
logged with its cause, most `ERROR` records carried no error classification, and the one that did
wrote it under keys the schema does not define. This supersedes the #70 sentence above saying the
error fields "are not remapped into `error.*`".

**The remap.** Call sites still write `error_code`, `error_category` and `error_follow_up_action`
— a dotted `error.code` would make the formatter write a second `error` object beside the one it
writes from the attached throwable — and `EcsErrorFieldsCustomizer`, the second customizer in
`logging.yaml`, moves them into the `error` object on the way out, as the standard's
custom-encoder recipe prescribes. It drops the formatter's own `error` member and the three
underscore keys, and writes one `error` object carrying `type`, `message` and `stack_trace` — from
the same accessors, printer and converter the formatter uses, so a record with an exception and
no classification encodes exactly as before — plus `code`, `category` and `follow_up_action`. A
record with neither gets no `error` object. `EcsErrorFieldsCustomizerTests` encodes each record
with and without it and compares.

**Every `ERROR` is classified.** `LogEvent.atError(log, code, category, followUp)` is the one way
to open an `ERROR` record, and `be-log-error-without-error-fields` refuses `atError()`,
`error(...)` and `atLevel(...)` on a logger anywhere else in production code. `error.code` is the
HTTP status a request fault was answered with, or `500` off any request; `error.category` is
`database` or `application` (and `data` for a caller's refusal); `error.follow_up_action` is
`true` on every `ERROR`. The records:

| Record                                             | `error.code` | `error.category`           | cause attached                            |
| -------------------------------------------------- | ------------ | -------------------------- | ----------------------------------------- |
| scheduled job failed                               | `500`        | `database` / `application` | whole                                     |
| SCIM 5xx refusal (advice, and `ScimErrorDocument`) | the status   | `application`              | the refusal (advice); none (filter)       |
| SCIM unmapped integrity violation                  | `500`        | `database`                 | redacted copy                             |
| audit append failed                                | `500`        | `database`                 | none — see below                          |
| request record, 5xx no handler recorded            | the status   | `application`              | none                                      |
| app-wide handler, unexpected exception             | the status   | `application`              | whole; redacted for a data-access failure |

The audit-append alert attaches no exception because it has none: the `OperationalAlerts` port
carries the failure's type, deliberately, so the alert cannot leak what the event withheld. The
record says so in `app.error.cause_omitted`, and `event.reason` names the type.

`RedactedFaultException` (moved from `ScimExceptionHandler` to `observability`, unchanged) is the
redacted copy: the original stack under a message that is only a type name, with no cause chain.

**One app-wide handler.** `web.ApiExceptionHandler` answers for the application chain's
controller packages, named in its `basePackages` — never `scim`'s, whose advice renders SCIM error
documents. Two `ArchitectureTest` rules hold the list: every `@RestController` outside `scim` is in
it, and none in it is in `scim`. A controller's own `@ExceptionHandler` still answers first, and a
refusal made before a handler is chosen keeps the container's handling.

- An exception nothing else answers is one `ERROR` `http.request.fault` record (`process`,
  `error`) with the exception attached, `event.reason` its type, and the status; the client gets
  a `500` `ApiError` that says nothing about it.
- A refusal Spring MVC maps to a `4xx` — `MethodArgumentNotValidException`,
  `HandlerMethodValidationException`, an unreadable body, an unbindable parameter — is one `WARN`
  `http.request.refusal` record (`process`, `denied`) with `error.code` the status,
  `error.category` `data` and `error.follow_up_action` `false`; `event.reason` is the exception's
  type, never its message, which quotes the rejected value. A `5xx` Spring MVC maps is a fault.
- The body is `ApiError` — `status`, `code` (`invalid-request`, `request-refused`,
  `server-error`) and a fixed `detail` — with no member taken from the request or the failure,
  and no `message`, which the SPA reads as a password rule. `AdminAuditController` now hands its
  out-of-range page to the handler as a `ResponseStatusException` with the domain refusal as its
  cause (which `event.reason` then names), so every `400` of that operation has the one body.

**No double `ERROR`.** A handler that writes a request's fault `ERROR` marks the request
(`RequestFault`), and `RequestIdFilter` then writes that request's record at `WARN` without an
error classification: still the `5xx`, still `failure`, but not the same failure twice. The SCIM
advice's and `ScimErrorDocument`'s `5xx` records mark it too. A `5xx` nobody recorded — an
exception escaping the chain — keeps its `ERROR` request record, now classified.

| Operation                   | `event.action`     | `app.event.action`     | `event.category` | `event.type` |
| --------------------------- | ------------------ | ---------------------- | ---------------- | ------------ |
| request refused by the API  | — (no action fits) | `http.request.refusal` | `process`        | `denied`     |
| request failed unexpectedly | — (no action fits) | `http.request.fault`   | `process`        | `error`      |

## Addendum (2026-10-02): user.id on the request record, and every record's event.action

Issue #95, findings LOG-N1 and LOG-N4 of the App-Standards re-audit
(`Structured_Logging_Application_Standard.md` §2 #1, §3.1; `Log_Schema.md` §Event).

**Who made the request.** The request record is the record an investigation starts
from, and it named nobody: `RequestIdFilter` writes it outside the security chain, and
the `user.id` and `scim.connector.id` scopes are opened inside the chain, by
`SessionUserLogContextFilter` and `ScimBearerAuthenticationFilter`, and closed as it
unwinds. Each of those filters now also marks the id it resolved on the request
(`RequestActor`), and `RequestIdFilter` puts the marks back in the logging context for
the one record it writes, then restores the context. Moving the record inside the chain
was rejected: a request the chain refuses before any inner filter runs would lose its
record, which is exactly the record the request-record addendum above exists to keep.

So the request record carries `user.id` when the request was authenticated by session,
and `scim.connector.id` when it was authenticated by a SCIM bearer token. That includes
a bearer call refused for a missing Permission, which did authenticate. Neither field appears on an
anonymous request, or on one refused before it authenticated (a missing session, or a
token that was not accepted). Only resolved ids are marked, never a userName or anything
of a presented token. The record stays one per request, and the probes and the scrape
still get none.

**`event.action` on every record.** The schema requires `event.action`, from a closed
list. Every `Operation` now maps onto its nearest allowed value, with
`app.event.action` naming the precise operation wherever the action is shared. Two
mappings changed. Dormant-authority revocation is now `user-administration`: it changes
a User's standing, as inactivity deactivation does. Creating and deleting a connector,
and revoking its token, are now `user-provisioning`, because they are the lifecycle of
the provisioning channel. Issuing and rotating a token are `user-administration`
(#117): each grants the token its Permissions. That leaves `access-control` to the
access decisions themselves: unlock, access denied and unauthenticated.

### Operations with no event.action

Five operations have no defensible allowed value and are recorded here as exceptions.
Each still carries a stable `app.event.action`. The allowed list names authentication,
provisioning, administration, password, session, access-control and lifecycle actions,
and all of them describe something done to or by an identity, or to the application.

| `app.event.action`     | Why no allowed value fits                                                                                                                                                                                                                 |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `http.request`         | It is the access record for every request, whatever the request did. Any one action would mislabel most requests, and `access-control` would merge every request into the authorization decisions a security search on that value is for. |
| `http.request.refusal` | It records the API refusing malformed input, not an identity operation or an access decision.                                                                                                                                             |
| `http.request.fault`   | It records an unexpected failure, which is not an operation of any kind.                                                                                                                                                                  |
| `audit.retention`      | It deletes aged-out audit rows. No User, session or access is involved.                                                                                                                                                                   |
| `audit.append`         | It records an audit write failing, which is an operational alert rather than an identity operation.                                                                                                                                       |

`LogEventTests.everyOperationHasAnActionOrADocumentedException` holds this list to the
code. An operation with no action that is not listed here fails the test, and so does a
listed operation that has gained an action.

### The full mapping

This table supersedes the per-addendum tables above for `event.action` and
`app.event.action`. Their category and type columns still stand.

| `Operation`                | `event.action`                | `app.event.action`                  |
| -------------------------- | ----------------------------- | ----------------------------------- |
| `LOGIN`                    | `user-authentication`         | —                                   |
| `EPIC_LOGIN`               | `user-authentication`         | `epic.login`                        |
| `EPIC_OUTBOUND`            | `user-authentication`         | `epic.outbound`                     |
| `EPIC_JWKS_REFETCH`        | `user-authentication`         | `epic.jwks_refetch`                 |
| `UNLOCK`                   | `access-control`              | `identity.unlock`                   |
| `FORCE_PASSWORD_CHANGE`    | `password-change-enforcement` | —                                   |
| `PASSWORD_CHANGE`          | `user-administration`         | `identity.password_change`          |
| `DORMANCY`                 | `user-administration`         | `identity.dormancy`                 |
| `DORMANCY_LOCKOUT`         | `user-administration`         | `identity.dormancy_lockout`         |
| `DORMANCY_ROLE_REVOCATION` | `user-administration`         | `identity.dormancy_role_revocation` |
| `CONNECTOR_CREATE`         | `user-provisioning`           | `scim.connector.create`             |
| `CONNECTOR_DELETE`         | `user-provisioning`           | `scim.connector.delete`             |
| `CONNECTOR_TOKEN_ISSUE`    | `user-administration`         | `scim.connector.token.issue`        |
| `CONNECTOR_TOKEN_ROTATE`   | `user-administration`         | `scim.connector.token.rotate`       |
| `CONNECTOR_TOKEN_REVOKE`   | `user-provisioning`           | `scim.connector.token.revoke`       |
| `SCIM_WRITE`               | `user-provisioning`           | `scim.write`                        |
| `SCIM_REFUSAL`             | `user-provisioning`           | `scim.refusal`                      |
| `ACCESS_DENIED`            | `access-control`              | `access.denied`                     |
| `UNAUTHENTICATED`          | `access-control`              | `access.unauthenticated`            |
| `LOGOUT`                   | `user-logout`                 | —                                   |
| `ROLE_GRANT`               | `user-administration`         | `identity.role_grant`               |
| `ROLE_REVOKE`              | `user-administration`         | `identity.role_revoke`              |
| `ROLE_MAPPING_STARTUP`     | `application-startup`         | `authorization.role_mapping`        |
| `SESSION_START`            | `session-start`               | —                                   |
| `SESSION_END`              | `session-end`                 | —                                   |
| `AUDIT_RETENTION`          | — (exception, above)          | `audit.retention`                   |
| `AUDIT_APPEND`             | — (exception, above)          | `audit.append`                      |
| `HTTP_REQUEST`             | — (exception, above)          | `http.request`                      |
| `HTTP_REQUEST_REFUSAL`     | — (exception, above)          | `http.request.refusal`              |
| `HTTP_REQUEST_FAULT`       | — (exception, above)          | `http.request.fault`                |
| `APPLICATION_STARTUP`      | `application-startup`         | —                                   |
| `APPLICATION_SHUTDOWN`     | `application-shutdown`        | —                                   |

## Addendum (2026-10-02): `session-start`

Issue #96. Logout, absolute-lifetime expiry and revocation each wrote `session-end`, but nothing
marked a session's start (`Recipes/Logging_AuthN_And_AuthZ_Events.md` §6.1, `Log_Schema.md`
§Event `session-start`).

**One record per signed-in session, written by the login.** The login writes one — since the
Epic Login prefactor, from `SessionEstablishment`, the session step `AuthController.login`
delegates to (still under the `AuthController` logger) —
`INFO` `session-start` once the authentication is saved into the session — after the id has
rotated — with `event.outcome` `success`, `event.severity` `low`,
`session.max_inactive_interval` (the idle bound read off that session, in seconds, the same
figure the login answers the SPA with), and `user.id` set for the record. A refused login creates
no session and writes none.

**Not on container session creation, unlike the recipe.** The recipe listens for
`HttpSessionCreatedEvent`. Here the only session created before authentication is the anonymous
one `GET /api/auth/csrf` mints to hold the token a login submits: it either becomes the
signed-in session (renamed, not recreated) or idles out unused, so recording its creation would
add a record per page load naming no one, and a `session-start` with no `session-end` for every
abandoned one. The CSRF grant therefore writes no `session-start`. Writing at the login also lets
the record carry `user.id`, which the recipe's creation-time record cannot; the recipe's note
omits it only because no one has authenticated yet when that event fires.

**Never the session id.** The record carries neither the id nor the cookie value (the id
Base64-encoded), before or after rotation: the id is the session's bearer credential.
`SessionStartLogIntegrationTests` searches the raw encoded stream of a real-socket CSRF grant and
login for all four spellings.

**Idle expiry stays unlogged.** Observing it needs Redis keyspace notifications, which
`session.yaml` leaves off (`configure-action: none`) because ElastiCache disables `CONFIG`; the
infrastructure is unchanged, as recorded under the #69 addendum above.

| Operation               | `event.action`  | `app.event.action` | `event.category` | `event.type` |
| ----------------------- | --------------- | ------------------ | ---------------- | ------------ |
| session started (login) | `session-start` | —                  | `process`        | `start`      |

## Addendum (2026-10-03): Role changes and the role mapping at startup (#116)

Three operations, each on an existing `event.action` value:

| Operation                                  | `event.action`        | `app.event.action`           | `event.category` | `event.type` | Level  |
| ------------------------------------------ | --------------------- | ---------------------------- | ---------------- | ------------ | ------ |
| Role gained through a mapped Group         | `user-administration` | `identity.role_grant`        | `process`        | `change`     | `INFO` |
| Role lost through a mapped Group           | `user-administration` | `identity.role_revoke`       | `process`        | `change`     | `INFO` |
| Role mapping validated, with its hash      | `application-startup` | `authorization.role_mapping` | `configuration`  | `info`       | `INFO` |
| Sessions ended for a mapping-hash mismatch | `application-startup` | `authorization.role_mapping` | `configuration`  | `change`     | `INFO` |

A Role change names the User as `user.target.id`, the Group as `group.id` (its
stable id, never its `displayName`) and the Role as `app.authorization.role`; the
startup records carry the mapping's SHA-256 as `app.authorization.mapping_hash`, and
the second one `session.ended_count`. Naming the Role is allowed here and only here:
an authorization refusal still names no Permission, Role or rule.

`ROLE_MAPPING_STARTUP` shares `application-startup` with `APPLICATION_STARTUP`, so it
carries a local name; the lifecycle record is the one `application-startup` record
with no `app.event.action`. `LogEventTests.everyOperationIsIdentifiableFromItsRecord`
was restated to say exactly that: an operation without a local name has an action no
other operation without a local name shares.

## Addendum (2026-10-04): the dormancy job (#118, ADR 0011)

One job replaces inactivity deactivation and dormant-authority revocation, so their
operations `identity.inactivity_deactivation` and
`identity.dormant_authority_revocation` — and the tables above that name them — are
retired, as are the `dormancy.window` and `dormancy.processed` fields and the old
jobs' per-run summary record. In their place, each on an existing `event.action` value:

| Operation                            | `event.action`        | `app.event.action`                  | `event.category` | `event.type`            | Level  |
| ------------------------------------ | --------------------- | ----------------------------------- | ---------------- | ----------------------- | ------ |
| `DORMANCY`, schedule at startup      | `user-administration` | `identity.dormancy`                 | `configuration`  | `info`                  | `INFO` |
| `DORMANCY`, run start / end          | `user-administration` | `identity.dormancy`                 | `batch`          | `job-start` / `job-end` | `INFO` |
| `DORMANCY_LOCKOUT`, per User         | `user-administration` | `identity.dormancy_lockout`         | `process`        | `change`                | `WARN` |
| `DORMANCY_ROLE_REVOCATION`, per User | `user-administration` | `identity.dormancy_role_revocation` | `process`        | `change`                | `INFO` |

The startup record carries `dormancy.lockout.window` and
`dormancy.role_revocation.window`. The run's `job-end` now carries what it counted —
`dormancy.locked_count` and `dormancy.roles_revoked_count` — through
`SkippableJobRun.counts()`, which any lock-serialized job may fill and a skipped run
never reports; there is no separate summary record. The per-User records name the
User as `user.target.id` and, for a revocation, the Roles lost as
`app.authorization.role`, comma-joined; never a `userName`. A dormancy lockout is
`WARN`, not the `ERROR` the authentication recipe gives a lockout, because it signals
inactivity rather than an attack. The `application-startup` record's two window
fields are now `app.dormancy.lockout.window` and
`app.dormancy.role_revocation.window`.

## Addendum (2026-10-04): records built per outcome, not at the call site (#128)

The Decision and the addenda above made `LogEvent` the one place that NAMES an event. Each call
site still built the rest of the record by hand: the outcome, the duration, and a message chosen
there — about ninety hand-added fields in 22 files. Adding one standard field to every refusal
meant editing every file that wrote one. `LogEvent` now builds the whole record.

**The shapes.** Each is a public method that opens the record at its level, classifies it, sets
`event.outcome` and `event.duration_ms` where the shape has them, and sets the operation's fixed
message for that shape. The caller names the operation, adds only the ids and counts it alone can
supply, and calls `log()`.

| Shape           | Level     | Sets                                                                        |
| --------------- | --------- | --------------------------------------------------------------------------- |
| `success`       | `INFO`    | `event.outcome` `success` (an overload adds `event.duration_ms`)            |
| `successAtWarn` | `WARN`    | the same, for the dormancy lockout the #118 addendum puts at `WARN`         |
| `refused`       | `WARN`    | `event.outcome` `failure`; the caller adds `event.reason`                   |
| `error`         | `ERROR`   | the §Error fields with follow-up `true`, `event.outcome` `failure`          |
| `jobScheduled`  | `INFO`    | `batch.job.name`, `app.job.description`, `trigger.cron.*`                   |
| `jobStart`      | `INFO`    | —                                                                           |
| `jobEnd`        | `INFO`    | `success`, `event.duration_ms`, and `event.reason` `lock-held` when skipped |
| `jobFailed`     | `ERROR`   | `error.code` `500`, `failure`, `event.severity` `high`, `event.duration_ms` |
| `jobSummary`    | `INFO`    | nothing: a run's outcome is its `job-end`'s                                 |
| `requestEnd`    | by status | `http.response.status_code`, `event.duration_ms`, `event.outcome`           |

`classify` and `atError` are no longer public: every record starts in a shape. `withError` stays
public, for the one refusal that carries a `data` error classification (`http.request.refusal`).
`ScheduledJobMetrics.scheduled` became `LogEvent.jobScheduled`, and the request record's level
rule moved from `RequestIdFilter` into `requestEnd`.

**One message per operation per shape.** The messages are tables in `LogEvent`, one per shape.
An operation a shape has no entry for gets that shape's generic message ("Operation completed")
rather than an exception, because a record is never worth failing the path that writes it.
Field names, values and levels are unchanged. One operation is keyed by type as well: the
role-mapping startup pass writes a `change` record for the sessions it ended beside the `info`
record of the validated hash, both under `authorization.role_mapping` as the role-mapping
addendum requires, so the `change` record keeps its own message ("Sessions issued under another role
mapping ended") rather than reading as a routine validation. One message changed, because one
operation wrote two different messages for one shape:

| Record                                              | Was              | Now                                                                  |
| --------------------------------------------------- | ---------------- | -------------------------------------------------------------------- |
| `session-end` from a revocation (`AccountSessions`) | `Sessions ended` | `Session ended`, as the absolute-lifetime `session-end` already said |

**The rule.** Semgrep `be-log-record-outside-log-event` refuses, in production code outside
`LogEvent`, any record opened on a logger at a level below `ERROR` (`be-log-error-without-error-fields`
already holds `ERROR`), the classic `info(...)`/`warn(...)`/`debug(...)`/`trace(...)` calls, a
write of `event.outcome` or `event.duration_ms`, `setMessage(...)`, and a `log(...)` call that
passes a message. Run against the code before this change it reported 100 findings across the 22
files. The redaction rules and the field names are unchanged.

## Addendum (2026-10-07): Epic Login's outbound calls (ADR 0013, D23, D25, D26)

Epic Login's calls to Epic — discovery, the JWKS and the token call — are the first records
of a call this service makes rather than answers. Two operations and four shapes are added,
each on an existing `event.action` value, since ADR 0013 puts every Epic record under
`user-authentication`:

| Record                                            | Shape                | Level   | `app.event.action`  | `event.category` | `event.type`          |
| ------------------------------------------------- | -------------------- | ------- | ------------------- | ---------------- | --------------------- |
| "Epic outbound call started"                      | `outboundStart`      | `INFO`  | `epic.outbound`     | `network`        | `connection`, `start` |
| "Epic outbound call completed", whatever the code | `outboundEnd`        | `INFO`  | `epic.outbound`     | `network`        | `connection`, `end`   |
| "Epic outbound call failed": no answer at all     | `outboundFailed`     | `ERROR` | `epic.outbound`     | `network`        | `connection`, `error` |
| a JWKS refetch for an unknown `kid`               | `jwksRefetchWarning` | `WARN`  | `epic.jwks_refetch` | `network`        | `connection`, `start` |
| the `kid` still unknown after the refetches       | `error`              | `ERROR` | `epic.jwks_refetch` | `network`        | `error`               |
| "Epic sign-in failed": an Epic call that answered | `error`              | `ERROR` | `epic.login`        | `network`        | `error`               |

**Fields.** The outbound records carry `app.epic.call` (`discovery`, `jwks` or `token`), the
method as `http.request.method`, and `url.full` narrowed to scheme, host, port and path —
never a query, a fragment or user info, any of which may carry a value no record may hold.
`outboundEnd` adds `http.response.status_code`, `event.duration_ms`, and `event.outcome`
`success` below `400`; `outboundFailed` adds `event.duration_ms` and the §Error fields:
`error.code` `502`, `error.category` `network`, follow-up `false`, and the failure attached as
a `RedactedFaultException` under its type name. `jwksRefetchWarning` carries `app.retry.attempt`
and no outcome, which is the retried operation's to state. Neither a body nor a header of any
Epic call is read for a record.

**Follow-up by category.** `error` gains an overload taking `error.follow_up_action`, because
ADR 0013's error-category table says per category whether an Epic failure needs a
person: `network` (a timeout, or no connection) and `server` (Epic's `5xx`) do not — Epic
being down is not this service's to fix, and the clinician is told to retry — while
`cert/auth` (Epic refusing our own credential) and `data` (an answer that is not what was
asked for) do. Three `error.category` values join the declared ones for it: `network`,
`server` and `cert/auth`, all from `Log_Schema.md` §Error; `data` now also covers an
unusable answer from a service this one called.

An Epic call failure is one `ERROR`, beside the outbound record of the call itself. A `5xx`, a
refused credential or an unusable answer is an answer, so its outbound record is the `INFO`
"completed", and the `ERROR` is "Epic sign-in failed", written when the Login ends for it. A
call that got no answer has its `ERROR` already — the outbound "failed", with the stack — so
the Login ending for it writes no second one (2026-10-09, below).

## Addendum (2026-10-08): D22's names (ADR 0013)

Epic Login handles values that must never reach a log record or the audit trail (ADR 0013,
D22): the authorization `code`, `launch`, `state`, `nonce`, the PKCE verifier, the `id_token`,
the access token, the client assertion, and the private key material of the active and next
signing keys. The first line of defence is the one the Decision above already holds every call
site to: no code path hands any of them to a logging or audit call. These names now join the
redaction as a second line behind it.

**The scan.** `be-log-sensitive-value` refuses a value whose name says it is a secret being
passed to a logging call. Its pattern gains a second family of names beside the original one:

- The original family matches anywhere in the argument (`newPassword`, `token.value()`), and
  already covers `id_token` and `access_token`, both being tokens.
- The new family is D22's remaining names — `code`, `authorizationCode`, `state`, `nonce`,
  `launch`, `verifier` / `codeVerifier` / `code_verifier`, `assertion` / `clientAssertion`,
  `privateKey`, `pem`, `clientKey`, `clientNextKey`, `APP_EPIC_CLIENT_KEY` and
  `APP_EPIC_CLIENT_NEXT_KEY` — matched as a **whole identifier**: a segment of the argument
  bounded by characters that cannot be part of a Java name or a kebab-case property. Matching
  them anywhere, as the first family does, would refuse `statusCode`, `ERROR_CODE` and
  `stateRule`, which are fine to log.
- The signing keys' `kid`s (`clientKeyId`, `APP_EPIC_CLIENT_KEY_ID` and its next-key twin) are
  deliberately outside it, and the whole-identifier match is what keeps them out: a `kid` is
  public — Epic reads it from our JWKS — and the startup record logs both by design (ADR 0013,
  D14), so that each key promotion leaves a record.

One existing call site matched and is suppressed with its reason: the failed-call `ERROR` passes
`failed.code()`, the failure's `error.code` — the HTTP status it maps to, never anything Epic
sent. It was `EpicLoginFailureHandler`'s, and is `EpicLoginOutcomeService`'s since the addendum
of 2026-10-09.

**The test.** `EpicLoginRedactionIntegrationTests` extends the redaction tests above
(`EcsLogFormatTests`, `JdbcErrorLogRedactionTests`) to Epic Login. It drives a successful Login,
one refused for each reason the audit trail knows, and each way Epic can be unavailable past
discovery, through the in-JVM fake Epic the Epic integration tests share, captures the whole
encoded log stream with `EcsLogCapture` and the whole audit trail after it, and asserts neither
holds any D22 value that Login actually handled — each gathered from both ends of the Login, and
the signing keys' material as PEM body and as the JWK `d`. Discovery failing needs a context whose
discovery has never succeeded, so `EpicDiscoveryIntegrationTests` holds those paths to the same
property. The test browser sends its parameters as a real query string, so a record that read the
query would fail it as surely as one that read a parameter.

**The retired spec.** The Epic Login spec this ADR's 2026-10-07 addendum cited was retired into
ADR 0013 with this change. That addendum's two references to it — the Epic records' placement
under `user-authentication`, and the per-category follow-up table — were repointed in place to
ADR 0013, which carries both unchanged. Nothing else in that addendum was edited, and what it
decided stands as written.

## Addendum (2026-10-09): one record per Epic Login ending

Epic Login's endings are recorded by one module, `EpicLoginOutcomeService`, rather than by the
login decision and the two Epic handlers in turn. Three things change in what they emit:

- **No second `ERROR` for a timeout.** Logging §3.3 says one event is logged once. A call that
  got no answer was logged at `ERROR` by the outbound interceptor that saw it fail, so "Epic
  sign-in failed" is no longer written for it. A `5xx` still has its one "Epic sign-in failed",
  because its outbound record is an `INFO`. The interceptor's record stays the one because it is
  also the only record of a failure no Login ends in (discovery reread after the JWKS refetches).
- **The MFA factor on the accepted record.** An accepted Epic Login's `user-authentication`
  record carries `app.login.mfa_factor` (ADR 0013, D17), so the operational stream has the
  factor that Logging §2.2 asks for, not just the audit row.
- **`session.hash` on every ending's record.** The accepted, refused and failed-call records of
  an Epic Login carry `session.hash`: the first 64 bits of the SHA-256 of a session id
  (`SessionHash`), and never the id, which is the session's bearer credential. A refusal names
  the session the Login ran in. A success names the session it signed in, the one the User goes
  on to use, and is recorded once that session is established, so after the login decision's
  commit. A refusal names no user, so before this it could be correlated by `trace.id` alone
  (SSO §3.4). A session id is a random UUID, so its hash cannot be guessed back the way a
  password's can. `be-log-sensitive-value` matches any value named `hash`, but not the call that
  adds this field, so nothing is suppressed for it. `session-start` and the password Login
  records do not carry it yet.
