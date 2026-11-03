---
type: Log Reference
title: Backend log records
description: Every operation's event.action and app.event.action, the shapes LogEvent builds records in, what each ERROR record attaches, and every record the backend writes with the fields it carries.
tags: [logging, ecs, observability, audit]
---

# Backend log records

What the backend writes to its log. `LogEvent` is the authority, and
`LogEventTests` and `OperationMessagesTests` hold it to its call sites; this is
their written copy. Why each rule is what it is lives in
`/docs/adr/0003-ecs-structured-logging-with-redaction.md`, and the variables in
`docs/configuration/logging.md`.

## Operations

| `Operation`                      | `event.action`         | `app.event.action`                        |
| -------------------------------- | ---------------------- | ----------------------------------------- |
| `LOGIN`                          | `user-authentication`  | —                                         |
| `EPIC_LOGIN`                     | `user-authentication`  | `epic.login`                              |
| `EPIC_OUTBOUND`                  | `user-authentication`  | `epic.outbound`                           |
| `EPIC_JWKS_REFETCH`              | `user-authentication`  | `epic.jwks_refetch`                       |
| `UNLOCK`                         | `access-control`       | `identity.unlock`                         |
| `PASSWORD_CHANGE`                | `user-administration`  | `identity.password_change`                |
| `DORMANCY`                       | `user-administration`  | `identity.dormancy`                       |
| `DORMANCY_LOCKOUT`               | `user-administration`  | `identity.dormancy_lockout`               |
| `DORMANCY_MEMBERSHIP_REVOCATION` | `user-administration`  | `identity.dormancy_membership_revocation` |
| `CONNECTOR_CREATE`               | `user-provisioning`    | `scim.connector.create`                   |
| `CONNECTOR_DELETE`               | `user-provisioning`    | `scim.connector.delete`                   |
| `CONNECTOR_TOKEN_ISSUE`          | `user-administration`  | `scim.connector.token.issue`              |
| `CONNECTOR_TOKEN_ROTATE`         | `user-administration`  | `scim.connector.token.rotate`             |
| `CONNECTOR_TOKEN_REVOKE`         | `user-provisioning`    | `scim.connector.token.revoke`             |
| `SCIM_WRITE`                     | `user-provisioning`    | `scim.write`                              |
| `SCIM_REFUSAL`                   | `user-provisioning`    | `scim.refusal`                            |
| `ACCESS_DENIED`                  | `access-control`       | `access.denied`                           |
| `UNAUTHENTICATED`                | `access-control`       | `access.unauthenticated`                  |
| `LOGOUT`                         | `user-logout`          | —                                         |
| `PERMISSION_GRANT`               | `user-administration`  | `identity.permission_grant`               |
| `PERMISSION_REVOKE`              | `user-administration`  | `identity.permission_revoke`              |
| `GROUP_PERMISSIONS_CHANGE`       | `user-administration`  | `authorization.group_permissions_change`  |
| `PATIENT_SEED`                   | `application-startup`  | `patient.seed`                            |
| `SESSION_START`                  | `session-start`        | —                                         |
| `SESSION_END`                    | `session-end`          | —                                         |
| `AUDIT_RETENTION`                | — (exception, above)   | `audit.retention`                         |
| `AUDIT_APPEND`                   | — (exception, above)   | `audit.append`                            |
| `HTTP_REQUEST`                   | — (exception, above)   | `http.request`                            |
| `HTTP_REQUEST_REFUSAL`           | — (exception, above)   | `http.request.refusal`                    |
| `HTTP_REQUEST_FAULT`             | — (exception, above)   | `http.request.fault`                      |
| `APPLICATION_STARTUP`            | `application-startup`  | —                                         |
| `APPLICATION_SHUTDOWN`           | `application-shutdown` | —                                         |

`event.kind` is `event` on every record.

## Shapes

| Shape                                              | Level                     | Sets                                                                                                         |
| -------------------------------------------------- | ------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `success`                                          | `INFO`                    | `event.outcome` `success` (an overload adds `event.duration_ms`)                                             |
| `successAtWarn`                                    | `WARN`                    | the same, for the dormancy lockout                                                                           |
| `refused`                                          | `WARN`                    | `event.outcome` `failure`; the caller adds `event.reason` where one is told                                  |
| `error`                                            | `ERROR`                   | the error fields (below), `event.outcome` `failure`                                                          |
| `jobScheduled`                                     | `INFO`                    | `batch.job.name`, `app.job.description`, `trigger.cron.*`                                                    |
| `jobStart`                                         | `INFO`                    | —                                                                                                            |
| `jobEnd`                                           | `INFO`                    | `success`, `event.duration_ms`, `batch.job.status` `completed`, or `skipped` with `event.reason` `lock-held` |
| `jobFailed`                                        | `ERROR`                   | `error.code` `500`, `failure`, `batch.job.status` `failed`, `event.severity` `high`, `event.duration_ms`     |
| `requestEnd`                                       | by status                 | `http.response.status_code`, `event.duration_ms`, `event.outcome`                                            |
| `outboundStart` / `outboundEnd` / `outboundFailed` | `INFO` / `INFO` / `ERROR` | the outbound call's fields (Epic Login, below)                                                               |
| `jwksRefetchWarning`                               | `WARN`                    | `app.retry.attempt`, no outcome                                                                              |
| `deadlockRetried`                                  | `WARN`                    | `error.category` `database`, `app.retry.attempt` `1`, `event.reason` `deadlock`, no outcome (below)          |

`withError` is public for the one `WARN` that carries a `data` error
classification (`http.request.refusal`).

## Error records

| Record                                             | `error.code` | `error.category`                                       | cause attached                            |
| -------------------------------------------------- | ------------ | ------------------------------------------------------ | ----------------------------------------- |
| scheduled job failed                               | `500`        | `database` / `cert/auth` (mapping) / `application`     | whole                                     |
| SCIM 5xx refusal (advice, and `ScimErrorDocument`) | the status   | `application`                                          | the refusal (advice); none (filter)       |
| SCIM unmapped integrity violation                  | `500`        | `database`                                             | redacted copy                             |
| audit append failed                                | `500`        | `database`                                             | none, `app.error.cause_omitted`           |
| request record, 5xx no handler recorded            | the status   | `application`                                          | none                                      |
| request record, FHIR-path 4xx the handler answered | the status   | `data`                                                 | none                                      |
| app-wide handler, unexpected exception             | the status   | the mapping for a database failure, else `application` | whole; redacted for a data-access failure |
| Epic call with no answer                           | `502`        | `network`                                              | redacted copy                             |

## Records

### One record per request, at its end

`RequestIdFilter` runs at
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

### Startup and shutdown

`ApplicationLifecycleLog` writes `application-startup` on
`ApplicationReadyEvent`. It carries `host.name`, `host.ip`,
`spring.profiles.active`, and the effective value of each non-secret setting that
changes behaviour:

- `app.scim.enabled`;
- `app.dormancy.lockout.window` and `app.dormancy.membership_revocation.window`;
- `app.audit.retention.period`;
- Epic Login's `app.epic.enabled` and its two `kid`s (ADR 0012, D14), from its own
  startup record.

`application-shutdown` is written on `ContextClosedEvent`, with the uptime as
`event.duration_ms`. Each answers only for its own context. No datasource, Redis,
credential or identity setting is read. The session timeouts and the lockout
threshold are absent (ADR 0003, "The records"), and the lifecycle tests keep them
off.

### 401 and 403

Both chains record through `AccessRefusalLog`: one `WARN` per
exchange, recorded on the request dispatch and only the first time. It carries:

- `event.reason`, from a closed set: `no-session`, `session-expired`,
  `bearer-missing`, `bearer-invalid`, `insufficient-permissions` or `csrf`;
- the method and the route template the request would have matched
  (`RouteTemplates`, which asks the handler mapping without dispatching);
- the caller from the context;
- for a `403` only, the caller's-error classification: `error.code` `403`,
  `error.category` `cert/auth`, `error.follow_up_action` `false` (#128). A `401`
  carries none.

No reason names a role, matcher, Permission or authority. No part of a presented
token, not even a prefix or the lookup id, reaches a record.

### Login

Both login methods' endings are written by one module,
`LoginOutcomeService` (ADR 0001):

- An accepted Login is one `INFO` `user-authentication`, "Login accepted". It is
  written after its session is signed in, and carries `user.id`,
  `app.login.method` (`password` or `sso`) and `session.hash` of the signed-in
  session. An Epic one also carries `app.login.mfa_factor`.
- A refusal is one `WARN`, "Login refused" or "Epic sign-in refused". It carries
  `app.login.method` and `session.hash` of the session the Login ran in. It has no
  user field and no `event.reason`, so a locked or deactivated User's refusal reads
  exactly as a wrong password's. An Epic input refusal also names the field and the
  rule it broke. The refusal's reason goes to the audit trail and to the `login`
  counter's `reason` tag only (ADR 0012, "the account reasons are audit-only").

`session.hash` is the first 64 bits of the SHA-256 of a session id
(`SessionHash`), so a refusal, which names no user, can be correlated beyond
`trace.id`.

### Sessions

Only the session id (or the cookie value, which is the id
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
- A session that idles out in Redis is not observed (`session.yaml`,
  `configure-action: none`; ADR 0003, "The records").

### SCIM refusals

`ScimExceptionHandler` writes one `scim.refusal` record per
refusal: `WARN` for a 4xx, or `ERROR` with the exception for a 5xx. It carries:

- `event.reason`: the `scimType`, or the refusal's own name where SCIM defines none
  (`ScimErrorException.reason()`);
- `scim.resource.type`;
- `scim.resource.id`, only when the route's `{id}` is a UUID.

It never carries the detail or any submitted value. Refusals made before any handler
(`ScimRequestBodyLimitFilter`, and the dispatcher's and firewall's `sendError`s that
`ErrorDocumentFilter` answers) get the same record from `ScimErrorDocument`, with
the reason fixed by the status and no resource type. The
unmapped integrity violation keeps its own `scim.write` `ERROR`. The release gate's
`404` while the namespace is closed is not a refusal record: the closed namespace
should look like no namespace at all. `log-levels.yaml` turns
`DefaultHandlerExceptionResolver` and Hibernate's JDBC error logger `OFF`, because
both write unstructured records that quote what the caller or the driver sent.

### The app-wide error handler

A handler's failure is answered by the advice of
the request's **error namespace** (`web.ErrorNamespaces`), never by the handler's
package: `NamespaceExceptionResolver` hands it to `ApiExceptionHandler` for `/api`
(and the FHIR `OperationOutcome` under `/api/patient`) or `ScimExceptionHandler`
for `/scim/v2`. So connector administration, in `scim` but serving `/api`, gets
`ApiExceptionHandler`'s records and never a `scim.refusal`. The namespace is read
from the path as Spring routes on it, decoded, so a percent-encoded prefix
(`/%61pi/...`) is answered by that namespace's advice too. The chain's `401` and
`403` under `/api` carry `ApiError`, written by the entry point and the
access-denied handler rather than a `sendError`, so neither reaches Boot's error
page, which quoted the path.

- An exception nothing else answers is one `ERROR` `http.request.fault`, with
  `event.reason` set to its type. The client gets a `500` `ApiError` that says
  nothing about it.
- A `4xx` that Spring MVC maps (validation, an unreadable body, an unbindable
  parameter) is one `WARN` `http.request.refusal`, classified `data` with no
  follow-up. Its `event.reason` is the exception's type, never its message, which
  quotes the rejected value. Under `/api/patient` (#128) the handler writes no such
  record: a read of a patient adds none to the request's own, so the handler
  marks the request (`RequestRefusal`) and `RequestIdFilter` writes the
  `data` classification, with no follow-up, on the request record instead.
- The body is `ApiError`: `status`, `code` (`invalid-request`, `request-refused`,
  `server-error`) and a fixed `detail`, with no member taken from the request.

### Scheduled jobs

Every job runs through `ScheduledJobMetrics.instrumentLocked`
(ADR 0005), inside the observation that gives it its trace. The run:

- puts `batch.job.name`, `batch.job.run.id` (a fresh UUID) and `trigger.type`
  `scheduled` in the context;
- writes `job-start`;
- writes `job-end`, the run's one summary line: `event.outcome`, `event.duration_ms`
  and `batch.job.status` — `completed`, with the counts the run reported
  (`SkippableJobRun.counts()`) and what it did to each kind of record as
  `record=[{type, …counts}]` (`SkippableJobRun.records()`); `skipped`, with
  `event.reason` `lock-held` and nothing else; or `failed`, as `jobFailed`'s
  `ERROR`, with the `record` of what the run had committed: what its body last
  reported committed (`JobProgress.committed`), else what the job's schedule states a
  failed run committed (`ScheduledJobSpec.committedOnFailure`);
- rethrows a failure, and clears the context however the run ended.

Both ends are classified as the job's own `Operation`. The startup schedule record
(`jobScheduled`) states `app.job.description`, `trigger.cron.expression` and
`trigger.cron.timezone`. `trigger.type` is `scheduled`, the schema's own value, not
`cron`.

### Audit retention

A run writes one line beside its `job-start`: its `job-end`,
`record=[{type: audit_event, deleted: <n>, batches: <m>}]`. It deletes in batches
of at most 4,000, each committed on the retention pool as the retention login,
inside the run's lock transaction (ADR 0005), and reports each batch committed as
it commits (`JobProgress`). So a failed run is `failed` with the batches and rows
it had committed — all of them when the lock transaction's own commit failed
afterwards. There is no line per batch. A run skipped on a held lock is the one
`skipped` line.
`AuditRetentionRunLogTests` holds each case as the scheduler runs it.

### Dormancy

`DORMANCY_LOCKOUT` and `DORMANCY_MEMBERSHIP_REVOCATION`
are one record per User, naming it as `user.target.id`. A revocation also names the Permissions the User lost — held before the
removals and not after — as `app.authorization.permissions_lost`, sorted and
comma-joined; the Groups removed are the audit event's to list. The run's `job-end` carries
`dormancy.locked_count` and `dormancy.memberships_revoked_count`, and the schedule record
carries both windows. A dormancy lockout is `WARN` (ADR 0003, "The records").

### Membership changes of power

A User gaining or losing direct
membership of a Group that confers Permissions, or of the Admin group, is one
`INFO` `change` record (`identity.permission_grant` or
`identity.permission_revoke`). It names the User as `user.target.id`, the Group as
`group.id` (never its `displayName`) and the Permissions the Group confers as
`app.authorization.group_permissions`, sorted and comma-joined. This and the dormancy
revocation are the only records that name a Permission (ADR 0003, "The records").

### A Group's Permissions set by the Superuser

Each accepted
change is one `INFO` record classified `user-administration` / `admin`, `group`,
`change` / `success` (`authorization.group_permissions_change`), naming the Group
as `group.id`; a refused one is the same operation at `WARN`, `denied`, with its
generic reason (`INVALID_PERMISSIONS`, `UNKNOWN_RESOURCE`, `PROTECTED_RESOURCE`) as
`event.reason`. Neither names a Permission: what the Group carried before and
carries after is the audit trail's (`GROUP_PERMISSIONS_CHANGE`). The
authorization-change counter's `group-permissions-change` tag is a metric tag, not
a log field: `event.action` stays on the standard's closed list.

### Epic Login's outbound calls

ADR 0012 decides the calls (D23, D25, D26). `EpicOutboundInterceptor`
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
the Login ends. Its follow-up flag comes from ADR 0012's error-category table:
`network` and `server` need no person, while `cert/auth` and `data` do.
