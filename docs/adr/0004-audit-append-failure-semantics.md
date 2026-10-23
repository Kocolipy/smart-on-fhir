# 4. Audit append failure semantics: fail-closed on a write, fail-open on a refusal

Date: 2026-09-25

## Status

Accepted. This file states the current position. Last revised 2026-10-23: the
rule's extensions to Session revocation, the event kinds, the append pool, the
edge recording of refusals and the database's append-only enforcement are folded
into the decision. History is in git.

## Context

Two requirements about what happens when an audit append itself fails
contradict each other on one path:

1. a write whose audit insert cannot commit rolls the triggering mutation back
   (**fail-closed**);
2. a failure-event append that itself fails raises an operational alert
   **without altering the original request's outcome** (**fail-open**).

A rejected login is both: a refusal, so (2) applies, and a write — the failure
run lengthens and may impose a lockout — so (1) seems to. Applying (1) would turn
a rejected login whose audit insert failed into a `500`.

## Decision

### Which semantics an append gets

It is decided by **what the triggering request was going to return**, not by
whether a row was written:

- **Fail-closed** where the request would otherwise **succeed**: an Unlock,
  setting a Group's Permissions, an accepted login, a logout, a SCIM write. The
  append joins the caller's transaction, so an event that cannot be written takes
  the mutation with it. A change this service cannot account for does not happen,
  and a session it cannot account for is not issued.
- **Fail-open with an operational alert** where the request is **already being
  refused**: `LOGIN_FAILURE` and the `LOCKOUT_SET` it may bring, and every refused
  administrative or SCIM write. These commit independently of the caller, so a
  rollback cannot take the failure-run write with it, and a failure raises
  `OperationalAlerts.auditAppendFailed` instead of propagating. There is no
  fail-open lift: `LOCKOUT_LIFT` is only ever an administrator's Unlock, which is
  fail-closed.

The alert is a port with a logging adapter, so "an alert was raised" is a claim
a test can check, and where an alert goes stays a deployment decision.

### The rule is applied once, from the event's kind

`AuditTrail` has one method, `append(Audited)`. Every kind of event is a value in
the sealed `audit.domain.Audited`, declaring one of three kinds:

- `Audited.Accepted` — **fail-closed**: joins the caller's transaction
  (`PROPAGATION_REQUIRED`). A SCIM bulk read is one, though it is a read: an
  unrecorded enumeration of the directory is the gap the trail exists to close.
- `Audited.Refused` — **fail-open with an alert**. `LOCKOUT_SET` is one, though
  its outcome is a success: it rides the refusal that imposed it.
- `Audited.Committed` — recorded after its change committed: **fail-open with an
  alert**. `USER_SESSIONS_REVOKE` is the one.

`AuditTrailService.append` is the only place either semantics is expressed.
`AuditTrailServiceTests.everyKindOfEventFailsAsAdr0004Decides` walks the sealed
hierarchy against the same forced failure, so an event added without a decision
fails there. Each event also carries the changed paths it records
(`/docs/domain/authentication.md`).

### Session revocation

Every per-User revocation goes through `SessionRevocationService` (ADR 0002), and
it applies this axis to the two failures a revocation can meet.

- **The `USER_SESSIONS_REVOKE` append is fail-open with an alert.** It is
  appended after the commit, so there is no change left for a failed append to
  undo.
- **A failing session store is always recorded and alerted** — a
  `USER_SESSIONS_REVOKE` with outcome `FAILURE`, and
  `OperationalAlerts.sessionRevocationFailed` — and then follows what the
  request was going to return. Where it would otherwise **succeed** (a
  self-service password change, an accepted Login's sweep, a SCIM write, a
  Group's Permissions reduced, the dormancy job) the failure **propagates**: the
  change stays durable, and the caller is told the request failed. Where it is
  **already refused** (a failure-run lockout from a rejected Login or password
  change) the failure is **swallowed**, so the answer stays the bare `401`. Then a
  locked User's sessions may outlive the lock while the store is down; the lock is
  committed, so no new Login succeeds, and the alert is what tells an operator to
  end them.
- A revocation that ended no session and did not fail records nothing.

### Fail-open appends commit on a pool of their own

A fail-open append once ran `PROPAGATION_REQUIRES_NEW` on the main transaction
manager, which keeps the caller's connection checked out while it opens a second
from the same pool — and a refusal is recorded while its caller holds a
connection and a row lock. Ten overlapping failed Logins, reachable before
sign-in, held Hikari's default ten connections, each waiting 30 s for one none
would release.

`Audited.Refused` and `Audited.Committed` appends therefore commit on a dedicated
audit append pool and never take a main-pool connection.

- The pool (`AuditAppendPoolConfig`) holds two or three connections (default 2,
  2 s timeout; `backend/docs/configuration/audit.md`) and connects exactly as the
  main pool does, as the app login.
- An append is one auto-committed JDBC `INSERT`
  (`IsolatedAuditEventPersistenceAdapter`): its own transaction by construction,
  so a caller that rolls back keeps its refusal on record.
- The pool's beans are not default candidates, so every other bean still gets the
  main pool and the one JPA transaction manager.
- `Audited.Accepted` appends join the caller's transaction on the main pool.

`AuditAppendPoolStarvationIntegrationTests` holds it: with every main-pool
connection held, a fail-open append completes and its event is in the trail.
Rejected: deferring the append keeps the connection checked out all the same; a
`PROPAGATION_NESTED` savepoint loses the event when the caller rolls back; a
bigger main pool only moves the threshold. The cost is two more connections per
instance, and a fail-open append fails after 2 s rather than 30 s in an outage.

### A refused write is recorded once, at the edge

Whether a refusal reached the trail once depended on which module noticed it
(architecture reviews 2026-10-13, B14, and 2026-10-22). Now the use cases raise a
typed refusal and record nothing, and the advice at the edge records each, once,
from one table:

- **SCIM writes.** `ScimExceptionHandler` records every refused write from
  `scim.controller.ScimRefusals`, naming each refusal's status, `scimType`, log
  reason and audit reason; the route the dispatcher matched names the refused
  event (`ScimUserCreateRejected`, `ScimUserWriteRejected`,
  `ScimUserDeleteRejected`, `ScimGroupCreateRejected`, `ScimGroupWriteRejected`).
  A refused precondition is not recorded.
- **Administrative operations** — Unlock, setting a Group's Permissions,
  connector and token administration. `auth.controller.AdminExceptionHandler`
  records each from `auth.controller.AdminRefusals`, naming every refusal's
  status, body, log record and refused event; the table is exhaustive over the
  two sealed refusal types, so a new refusal does not compile without its entry.
  Every refusal addressing something is audited, an unknown target included
  (`UNKNOWN_RESOURCE`); a token request of a shape no token may have is answered
  `400` and not audited.

Each row is `Audited.Refused`, appended fail-open after the use case's
transaction has rolled back and released its connection.

### The database keeps the trail append-only

Every append runs as the **app login** (`backend_app`), the retention job deletes
as the **retention login** (`backend_audit_retention`), and the owner login is
never held by the service (ADR 0013). The schema holds the rule in three parts,
each covering a hole the others leave:

- **Grants.** `backend_app` holds `SELECT, INSERT` on `audit_events`, so its
  `UPDATE` or `DELETE` is refused (error 229); `backend_audit_retention` holds
  `SELECT, DELETE`.
- **An `INSTEAD OF UPDATE, DELETE` trigger** (`audit_events_append_only`) refuses
  every `UPDATE` from every principal, and every `DELETE` unless the caller is in
  `backend_audit_retention` and not in `db_owner` — so the owner login, which the
  grants do not bind, is refused too.
- **A refusal number of its own.** The trigger refuses with `THROW 50001`, never
  229, because the two need different people (ADR 0003, "The database category
  mapping"): a permission refusal is `cert/auth`, while the trigger's is
  `database` — something tried to rewrite the audit trail.

`AuditAppendOnlyIntegrationTests` connects as each login and as the server
administrator and asserts each refusal or admitted `DELETE`.

## Consequences

- A rejected login always answers with the same bare `401`, whatever the state
  of the trail; a `500` would tell whoever submitted the credentials something
  about the service's internals.
- While the trail is unavailable, failed logins and the lockouts they cause
  happen **unrecorded**. The only evidence is the `ERROR` alert per lost event,
  naming the operation — which is why it is `ERROR`, not `WARN`.
- A new audited event declares its kind in `Audited`, or `AuditTrailServiceTests`
  fails. A new event on the rejected-login path must be `Refused`, or a refused login
  starts returning `500`; a new audited administrative write must be `Accepted`,
  or an unrecorded change becomes possible.

## Alternatives considered

**Fail-closed everywhere.** Rejected: a rejected login would return `500` when
the trail is unavailable, leaking internal state and replacing a correct answer.

**Fail-open everywhere.** Rejected: an administrator's change could then go
unrecorded, which is the single event the trail exists for.

**Append after commit, everywhere.** Rejected: every append becomes fail-open by
construction, and (1) becomes unexpressible.
