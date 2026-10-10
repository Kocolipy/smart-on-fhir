# 3. ECS-structured logging with redaction enforced structurally

Date: 2026-09-25

## Status

Accepted. Consolidated on 2026-10-10 from the original decision and the fifteen
addenda dated 2026-10-01 to 2026-10-10, ADR 0013's Epic Login among them. This file states the current position. The dated
addenda, including the rules they superseded, are in git history.

`LogEvent` is the authority on each operation's `event.action`,
`app.event.action`, level and message, and the call sites on its category and
type. The two tables under "The event vocabulary" are the written copy that
`LogEventTests` holds to it.

## Context

The service had no logging configuration and no application log statements at all:
whatever Boot's default pattern layout printed was the whole log stream.

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
- Where a record concerns a different User (unlock, force-change, a revocation, a
  Role change, a dormancy action), that User is `user.target.id`.
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
  shares (`LogEventTests.everyOperationIsIdentifiableFromItsRecord`).
  `ROLE_MAPPING_STARTUP` therefore carries a local name beside
  `APPLICATION_STARTUP`.

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

#### The full mapping

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

`event.kind` is `event` on every record.

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

| Shape                                              | Level                     | Sets                                                                        |
| -------------------------------------------------- | ------------------------- | --------------------------------------------------------------------------- |
| `success`                                          | `INFO`                    | `event.outcome` `success` (an overload adds `event.duration_ms`)            |
| `successAtWarn`                                    | `WARN`                    | the same, for the dormancy lockout                                          |
| `refused`                                          | `WARN`                    | `event.outcome` `failure`; the caller adds `event.reason` where one is told |
| `error`                                            | `ERROR`                   | the error fields (below), `event.outcome` `failure`                         |
| `jobScheduled`                                     | `INFO`                    | `batch.job.name`, `app.job.description`, `trigger.cron.*`                   |
| `jobStart`                                         | `INFO`                    | —                                                                           |
| `jobEnd`                                           | `INFO`                    | `success`, `event.duration_ms`, and `event.reason` `lock-held` when skipped |
| `jobFailed`                                        | `ERROR`                   | `error.code` `500`, `failure`, `event.severity` `high`, `event.duration_ms` |
| `jobSummary`                                       | `INFO`                    | nothing: a run's outcome is its `job-end`'s                                 |
| `requestEnd`                                       | by status                 | `http.response.status_code`, `event.duration_ms`, `event.outcome`           |
| `outboundStart` / `outboundEnd` / `outboundFailed` | `INFO` / `INFO` / `ERROR` | the outbound call's fields (Epic Login, below)                              |
| `jwksRefetchWarning`                               | `WARN`                    | `app.retry.attempt`, no outcome                                             |

`withError` is public for the one `WARN` that carries a `data` error
classification (`http.request.refusal`). Each shape has one message per operation.
An operation a shape has no entry for gets the shape's generic message ("Operation
completed") rather than an exception, because a record is never worth failing the
path that writes it.

Semgrep `be-log-record-outside-log-event` refuses, in production code outside
`LogEvent`:

- a record opened on a logger below `ERROR`;
- the classic `info(...)`, `warn(...)`, `debug(...)` and `trace(...)` calls;
- a write of `event.outcome` or `event.duration_ms`;
- `setMessage(...)`;
- a `log(...)` that passes a message.

### Error classification

**Every `ERROR` is classified.** `LogEvent`'s `ERROR` shapes (`error`, `jobFailed`,
`outboundFailed`) are the only ways to write one, and `be-log-error-without-error-fields` refuses any other
`ERROR` record in production code. The fields:

- `error.code` is the HTTP status a request fault was answered with, or `500` off
  any request.
- `error.category` is `database`, `application`, `data` (a caller's refusal, or an
  unusable answer from a service this one called), `network`, `server` or
  `cert/auth`.
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

| Record                                             | `error.code` | `error.category`           | cause attached                            |
| -------------------------------------------------- | ------------ | -------------------------- | ----------------------------------------- |
| scheduled job failed                               | `500`        | `database` / `application` | whole                                     |
| SCIM 5xx refusal (advice, and `ScimErrorDocument`) | the status   | `application`              | the refusal (advice); none (filter)       |
| SCIM unmapped integrity violation                  | `500`        | `database`                 | redacted copy                             |
| audit append failed                                | `500`        | `database`                 | none, `app.error.cause_omitted`           |
| request record, 5xx no handler recorded            | the status   | `application`              | none                                      |
| app-wide handler, unexpected exception             | the status   | `application`              | whole; redacted for a data-access failure |
| Epic call with no answer                           | `502`        | `network`                  | redacted copy                             |

A **redacted copy** (`RedactedFaultException`) is the original stack under a
message that is only the cause's type name, with no cause chain. It is used where
the message may quote data, as a JDBC driver's quotes the refused row. A failed
scheduled job attaches its exception whole, because Spring's scheduler error
handler logs it whole regardless and a copy would only cost `error.type` its real
class. The audit-append alert attaches nothing: `OperationalAlerts` carries only
the failure's type, so the alert cannot leak what the event withheld.

**No double `ERROR`.** A handler that writes a request's fault `ERROR` marks the
request (`RequestFault`), and `RequestIdFilter` then writes that request's record at
`WARN`, unclassified: still the `5xx` and still `failure`, but not the same failure
twice. An Epic call that got no answer is logged at `ERROR` once, by the outbound
interceptor, not again when the Login ends for it.

### The records

**One record per request, at its end.** `RequestIdFilter` runs at
`HIGHEST_PRECEDENCE + 2`. That is inside Boot's observation filter, so the record
carries the request's `trace.id`, and still ahead of the security chain, so a
request the chain refuses is recorded too. It writes the record from the `finally`
of the request dispatch, and the error dispatch writes none. The record carries:

- `http.request.method`, from a fixed set, `_OTHER` beyond it;
- `http.route`, the matched template, or `unmatched`;
- `http.response.status_code`, `event.duration_ms` and `event.outcome`
  (`success` below 400);
- `user.id` or `scim.connector.id` when the request authenticated.

The level is `INFO` below 400, `WARN` for a 4xx and `ERROR` for an unrecorded 5xx.
The actor's id comes back out of the security chain through `RequestActor`, a mark
on the request. The raw path, query string, headers, cookies, body and client
address are never read into the record. `/actuator/health` (and its probe
sub-paths) and `/actuator/prometheus` get no record: their status and timing are
already on `http.server.requests`.

**Startup and shutdown.** `ApplicationLifecycleLog` writes `application-startup` on
`ApplicationReadyEvent`. It carries `host.name`, `host.ip`,
`spring.profiles.active`, and the effective value of each non-secret setting that
changes behaviour:

- `app.scim.enabled`;
- `app.dormancy.lockout.window` and `app.dormancy.role_revocation.window`;
- `app.audit.retention.period`;
- Epic Login's `app.epic.enabled` and its two `kid`s (ADR 0013, D14), from its own
  startup record.

`application-shutdown` is written on `ContextClosedEvent`, with the uptime as
`event.duration_ms`. Each answers only for its own context. No datasource, Redis,
credential or identity setting is read. The session timeouts and the lockout
threshold are deliberately absent: the standard (§2 #0) forbids logging timeout or
retry values for authentication flows, and the lifecycle tests keep them off.

**401 and 403.** Both chains record through `AccessRefusalLog`: one `WARN` per
exchange, recorded on the request dispatch and only the first time. It carries:

- `event.reason`, from a closed set: `no-session`, `session-expired`,
  `bearer-missing`, `bearer-invalid`, `insufficient-permissions` or `csrf`;
- the method and the route template the request would have matched
  (`RouteTemplates`, which asks the handler mapping without dispatching);
- the caller from the context.

No reason names a role, matcher, Permission or authority. No part of a presented
token, not even a prefix or the lookup id, reaches a record.

**Login.** Both login methods' endings are written by one module,
`LoginOutcomeService` (ADR 0014):

- An accepted Login is one `INFO` `user-authentication`, "Login accepted". It is
  written after its session is signed in, and carries `user.id`,
  `app.login.method` (`password` or `sso`) and `session.hash` of the signed-in
  session. An Epic one also carries `app.login.mfa_factor`.
- A refusal is one `WARN`, "Login refused" or "Epic sign-in refused". It carries
  `app.login.method` and `session.hash` of the session the Login ran in. It has no
  user field and no `event.reason`, so a locked or deactivated User's refusal reads
  exactly as a wrong password's. An Epic input refusal also names the field and the
  rule it broke. The refusal's reason goes to the audit trail and to the `login`
  counter's `reason` tag only (ADR 0013, "the account reasons are audit-only").

`session.hash` is the first 64 bits of the SHA-256 of a session id
(`SessionHash`). A session id is a random UUID, so its hash cannot be guessed back
the way a password's can. The field lets a refusal, which names no user, be
correlated beyond `trace.id`.

**Sessions.** Only the session id (or the cookie value, which is the id
Base64-encoded) is the session's bearer credential, and no record carries either.
`SessionStartLogIntegrationTests` searches the raw stream for every spelling.

- `session-start` (`INFO`) is written by `SessionEstablishment` once the
  authentication is saved into the rotated session. It carries `user.id`,
  `app.login.method` and `session.max_inactive_interval`. A refused Login writes
  none, and so does the anonymous session `GET /api/auth/csrf` creates.
- `user-logout` (`INFO`) is written with `user.id`, after its audit append.
- `session-end` is written when a session ends by its absolute lifetime
  (`event.reason` `absolute-lifetime`, the session's own `user.id`). It is also
  written for a Session revocation (`SessionRevocationService`) that ended at least
  one session. Its `event.reason` is the revocation's causes: the
  `SessionRevocationCause` names, comma-joined in declaration order
  (`FAILURE_RUN_LOCKOUT`, `REPLACED_BY_LOGIN`, `DEACTIVATED,USER_NAME_CHANGED`).
  These are the same names its `USER_SESSIONS_REVOKE` audit event records. The
  record also carries the account as `user.target.id` and `session.ended_count`.
- A revocation the session store fails is an `ERROR` `session-end` alert
  (`OperationalAlerts.sessionRevocationFailed`) naming the failure's type.
- A session that idles out in Redis is not observed. Observing it needs Redis
  keyspace notifications, which `session.yaml` leaves off (`configure-action:
none`) because ElastiCache disables `CONFIG`.

**SCIM refusals.** `ScimExceptionHandler` writes one `scim.refusal` record per
refusal: `WARN` for a 4xx, or `ERROR` with the exception for a 5xx. It carries:

- `event.reason`: the `scimType`, or the refusal's own name where SCIM defines none
  (`ScimErrorException.reason()`);
- `scim.resource.type`;
- `scim.resource.id`, only when the route's `{id}` is a UUID.

It never carries the detail or any submitted value. Refusals made before any handler
(`ScimRequestBodyLimitFilter`, `ScimDispatcherErrorFilter`) get the same record from
`ScimErrorDocument`, with the reason fixed by the status and no resource type. The
unmapped integrity violation keeps its own `scim.write` `ERROR`. The release gate's
`404` while the namespace is closed is not a refusal record: the closed namespace
should look like no namespace at all. `log-levels.yaml` turns
`DefaultHandlerExceptionResolver` and Hibernate's JDBC error logger `OFF`, because
both write unstructured records that quote what the caller or the driver sent.

**The app-wide error handler.** `web.ApiExceptionHandler` answers for the
application chain's controller packages, never `scim`'s. Two `ArchitectureTest`
rules hold its `basePackages` list.

- An exception nothing else answers is one `ERROR` `http.request.fault`, with
  `event.reason` set to its type. The client gets a `500` `ApiError` that says
  nothing about it.
- A `4xx` that Spring MVC maps (validation, an unreadable body, an unbindable
  parameter) is one `WARN` `http.request.refusal`, classified `data` with no
  follow-up. Its `event.reason` is the exception's type, never its message, which
  quotes the rejected value.
- The body is `ApiError`: `status`, `code` (`invalid-request`, `request-refused`,
  `server-error`) and a fixed `detail`, with no member taken from the request.

**Scheduled jobs.** Every job runs through `ScheduledJobMetrics.instrumentLocked`
(ADR 0005), inside the observation that gives it its trace. The run:

- puts `batch.job.name`, `batch.job.run.id` (a fresh UUID) and `trigger.type`
  `scheduled` in the context;
- writes `job-start`;
- writes `job-end`, which carries `event.reason` `lock-held` for a skipped run, the
  counts the run reported (`SkippableJobRun.counts()`), or `jobFailed`'s `ERROR`;
- rethrows a failure, and clears the context however the run ended.

Both ends are classified as the job's own `Operation`. The startup schedule record
(`jobScheduled`) states `app.job.description`, `trigger.cron.expression` and
`trigger.cron.timezone`. `trigger.type` is `scheduled`, the schema's own value, not
`cron`.

**Dormancy** (ADR 0011). `DORMANCY_LOCKOUT` and `DORMANCY_ROLE_REVOCATION` are one
record per User, naming it as `user.target.id`. A revocation also names the Roles
lost as `app.authorization.role`, comma-joined. The run's `job-end` carries
`dormancy.locked_count` and `dormancy.roles_revoked_count`, and the schedule record
carries both windows. A dormancy lockout is `WARN`, not the `ERROR` the
authentication recipe gives a lockout, because it signals inactivity rather than an
attack.

**Role changes and the role mapping** (ADR 0010). A Role gained or lost through a
mapped Group is one `INFO` `change` record. It names the User as `user.target.id`,
the Group as `group.id` (never its `displayName`) and the Role as
`app.authorization.role`. Naming a Role is allowed here and only here: an
authorization refusal still names no Permission, Role or rule. At startup,
`authorization.role_mapping` writes an `info` record with the mapping's SHA-256 as
`app.authorization.mapping_hash`. When sessions issued under another mapping were
ended, it writes a `change` record too, with its own message ("Sessions issued under
another role mapping ended") and `session.ended_count`. The hash is configuration,
not a secret, so `be-log-sensitive-value` is suppressed on exactly those two
records.

**Epic Login's outbound calls** (ADR 0013, D23, D25, D26). `EpicOutboundInterceptor`
writes "Epic outbound call started" and "completed" (`INFO`, whatever the status),
or "failed" (`ERROR`, `network`, no answer at all). The records carry:

- `app.epic.call` (`discovery`, `jwks` or `token`);
- `http.request.method`;
- `url.full`, narrowed to scheme, host, port and path;
- on the end record, the status, `event.duration_ms` and `event.outcome`.

Neither a body nor a header is read. A JWKS refetch for an unknown `kid` is a
`WARN` with `app.retry.attempt`, and a `kid` still unknown after the refetches is
an `ERROR` (`data`). An Epic call that answered but ended the Login (a `5xx`, a
refused credential, an unusable answer) is "Epic sign-in failed" at `ERROR` when
the Login ends. Its follow-up flag comes from ADR 0013's error-category table:
`network` and `server` need no person, while `cert/auth` and `data` do.

### Redaction gates

The test can only speak for the flows it drives, and the scan speaks for call sites
that do not exist yet. Neither alone is the property.

- **Tests.** `EcsLogFormatTests` drives an accepted login, a refused login and an
  administrative change through the real filter chain. It encodes every record with
  the production encoder and asserts that no fixture credential or identifier
  appears anywhere in the bytes. `JdbcErrorLogRedactionTests` does the same for a
  driver error. `EpicLoginRedactionIntegrationTests` drives the following through
  the in-JVM fake Epic:
  - a successful Epic Login;
  - one refused for each audit reason;
  - each way Epic can be unavailable past discovery;
  - a refresh token on every path.

  It asserts that neither the log stream, the audit trail nor any answer to the
  browser holds a value of ADR 0013's D22 that the Login handled.
  `EpicDiscoveryIntegrationTests` holds the discovery-failure paths to the same
  rule.

- **Scans.** `be-log-message-concatenation` refuses a value concatenated into a
  message. `be-log-sensitive-value` refuses a value whose name says it is an
  identifier or a secret being passed to a logging call, including as a `{}`
  parameter. Its names come in two families:
  - Names matched anywhere in the argument: password, token (which covers
    `id_token`, `access_token` and `refresh_token`), hash, and similar.
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
  exists.
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
