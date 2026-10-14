# 4. Audit append failure semantics: fail-closed on a write, fail-open on a refusal

Date: 2026-09-25

## Status

Accepted.

The administrator's disable and enable named below were removed when `active`
became the directory's. The rule is unchanged and is the decision, not the list:
an event an otherwise-successful request records joins its transaction; refusal
events run fail-open, and so does the record of a post-commit session revocation,
whose transaction has already committed (`AuditTrailService`). How a failing
session store answers, on every revocation trigger, is the 2026-10-10 addendum below;
where the rule is expressed — once, from each event's kind — is the 2026-10-12
addendum; which connections a fail-open append commits on is the 2026-10-13
addendum; where a refused SCIM write is recorded is the second 2026-10-13 addendum.

To be superseded in part by ADR 0015 (2026-10-12, SQL Server 2022 replaces
PostgreSQL), in the database enforcement these appends write into: the audit trail
stays append-only through a retention login and a T-SQL trigger instead of a
retention role assumed with `SET LOCAL ROLE` and a PL/pgSQL trigger. The
fail-closed/fail-open axis this ADR decides is unchanged. The superseding note
lands with the ticket that changes the mechanism.

**Superseded in part (2026-10-14, #66): the role switch.** On PostgreSQL already,
the role-switching mechanism is gone. Every statement this ADR's appends write runs
as the **app login**, a member of `backend_app` only — the main pool and the audit
append pool both connect as it, with no `connection-init-sql` — and the retention
job deletes as the **retention login**, a member of `backend_audit_retention` only,
on a pool of its own; nothing issues `SET ROLE` or `SET LOCAL ROLE`. The retention
role keeps `SELECT, DELETE` (its `UPDATE` grant is revoked), and the trigger refuses
every `UPDATE` from every principal and every `DELETE` unless the caller is a
member of `backend_audit_retention` that does not administer it — so the owner
login, which migrates out of process and is never held by the service, is refused
too. Wherever this ADR or its addenda say "runtime role" or `SET ROLE backend_app`,
read the app login. The T-SQL trigger is still ADR 0015's.

## Context

The audit trail records what the application already does: accepted and refused
logins, logouts, lockouts imposed and lifted, accounts disabled and enabled. Two
requirements about what happens when an append itself fails were stated together,
and taken literally they contradict each other on one path:

1. a write whose audit insert cannot commit rolls the triggering mutation back
   (**fail-closed**);
2. a failure-event append that itself fails raises an operational alert **without
   altering the original request's outcome** (**fail-open**).

A rejected login is both. It is a refusal, so (2) applies. It also performs a write
— the failure run lengthens, and reaching the limit imposes a lockout — so (1)
looks like it applies as well. Applying (1) there would mean a rejected login whose
audit insert failed returns `500`, because the exception would leave
`LoginAttemptService` as something other than an `AuthenticationException`.

## Decision

Which semantics an append gets is decided by **what the triggering request was
going to return**, not by whether a row was written:

- **Fail-closed** where the request would otherwise **succeed**: an
  administrator's disable, enable or unlock, an accepted login, a logout. The
  append joins the caller's transaction and flushes, so an event that cannot be
  written takes the mutation with it. A change this service cannot account for does
  not happen, and a session it cannot account for is not issued.
- **Fail-open with an operational alert** where the request is **already being
  refused**: the `LOGIN_FAILURE` event and the `LOCKOUT_SET` it may be accompanied
  by. These appends run in a transaction of their own
  (`PROPAGATION_REQUIRES_NEW`), so a rollback there cannot take the caller's
  failure-run write with it, and a failure raises
  `OperationalAlerts.auditAppendFailed` instead of propagating.

  There is no fail-open lift. A lockout has no duration, so `LOCKOUT_LIFT` is only
  ever an administrator's unlock, which is a request that would otherwise succeed
  and is therefore fail-closed with everything else in that group.

The alert is a port with a logging adapter rather than a log call in the use case,
so "an alert was raised" is a claim a test can check without reading log bytes, and
so where an alert goes stays a deployment decision.

## Consequences

A rejected login always answers with the same bare `401`, whatever the state of the
audit trail. That is the point: turning it into a `500` would tell whoever submitted
the credentials something about the service's internal state, and would change the
answer to a question that had already been answered correctly.

The cost is a real one and worth stating plainly: while the trail is unavailable,
failed logins and the lockouts they cause continue to happen and are **not
recorded**. The only evidence is the `ERROR` alert per lost event, naming the
operation and the failure's type. An operator who ignores those alerts has a trail
with a hole in it and nothing in the trail itself will say so — which is why the
alert is `ERROR` rather than `WARN`, and why it names the missing operation.

The asymmetry is also a thing a reader has to know before changing either path. A
new audited event on the rejected-login path must be fail-open or a refused login
starts returning `500`; a new audited administrative write must be fail-closed or
an unrecorded change becomes possible. `AuditTrailService` is where both are
expressed, and `AuditTrailServiceTests` asserts the two behaviours against the same
forced failure so the difference cannot become an accident of which exception was
thrown.

## Alternatives considered

**Fail-closed everywhere.** Rejected: a rejected login would return `500` when the
trail is unavailable, which both leaks internal state and replaces a correct answer
with a wrong one.

**Fail-open everywhere.** Rejected: an administrator could then disable an account
with no record of who did it, which is the single event the trail exists for.

**Append after commit, everywhere.** Rejected: it makes every append fail-open by
construction, and the transactional guarantee in (1) becomes unexpressible.

## Addendum (2026-10-10): Session revocation, on every trigger

Session revocation became one module (`SessionRevocationService`) that every
per-User trigger goes through: a SCIM write, the dormancy job, a failure-run
lockout, a forced or self-service password change, and an accepted Login's
one-session-per-User sweep. Before, only the revocations requested through the
directory's port were audited; a failure-run lockout, a forced change and a Login's
sweep ended sessions unrecorded, and a session store that failed under a rejected
Login turned its `401` into a `500`. The module applies this ADR's axis to the two
failures a revocation can meet.

**The audit append is fail-open with an alert**, on every trigger. The
`USER_SESSIONS_REVOKE` event is appended after the commit, so there is no change
left for a failed append to undo; failing the request over it would only report an
error for a change that happened. That was already the rule for the SCIM
revocation, and it now covers the auth-path ones too.

**A session store that fails is always recorded and always alerted** — a
`USER_SESSIONS_REVOKE` event with outcome `FAILURE`, and
`OperationalAlerts.sessionRevocationFailed` — and then follows what the triggering
request was going to return:

- where the request would otherwise **succeed** — a forced password change, an
  accepted Login's sweep, a SCIM write, the dormancy job's lockout or role
  revocation, a self-service password change — the failure **propagates**. The
  change stays durable (ADR 0002), and the caller is told the request failed
  rather than that it succeeded while the sessions it should have ended survive;
- where the request is **already refused** — a failure-run lockout, imposed by a
  rejected Login or a rejected self-service password change — the failure is
  **swallowed**, so the answer stays the same bare `401` whatever the state of the
  session store, for the reason the rejected Login's own appends are fail-open.

The refusal case is the expensive one, and worth stating plainly: a locked User's
sessions may outlive the lock while the store is down, and only the `FAILURE`
event and the `ERROR` alert say so. The lock itself is committed, so no new Login
succeeds; the live sessions are what an operator acting on the alert must end.

A revocation that ended no session and did not fail records nothing — there is
nothing to account for — which matches the `session-end` log record, already
skipped at zero. This deliberately changes the SCIM revocation's earlier
always-audit behaviour.

## Addendum (2026-10-12): The rule is applied once, from the event's kind

Until now each of the trail's 42 `record*` methods chose its semantics by the
annotation it carried — `@Transactional` for fail-closed, a call to the alerting
append for fail-open — so the rule above held only as long as every new method was
annotated correctly, and nothing tested that it was.

`AuditTrail` now has one method, `append(Audited)`. Every kind of event is a value in
`audit.domain.Audited`, and each declares which of three kinds it is, named for what
the triggering request was going to return:

- `Audited.Accepted` — the request would otherwise succeed: **fail-closed**. The
  append joins the caller's transaction (`PROPAGATION_REQUIRED`, opening one where
  there is none), as the per-method `@Transactional` did. A SCIM bulk read is one,
  though it is a read: an unrecorded enumeration of the directory is the gap the
  trail exists to close.
- `Audited.Refused` — the request is already refused: **fail-open with an alert**,
  in a transaction of its own (`PROPAGATION_REQUIRES_NEW`). `LOCKOUT_SET` is one,
  though its outcome is a success: it rides the rejected Login or password change
  that imposed it.
- `Audited.Committed` — recorded after its change committed: **fail-open with an
  alert**, whatever its outcome. `USER_SESSIONS_REVOKE` is the one such event (the
  2026-10-10 addendum).

`AuditTrailService.append` reads the kind and applies the rule; it is the only place
either semantics is expressed. `AuditTrailServiceTests` asserts every kind of event
against the same forced failure, the kinds found from the sealed hierarchy and the
expected semantics written out kind by kind, so an event added without a decision
fails there. The semantics of every event are unchanged.

Each event also carries the changed paths it records. Writing them beside the events
corrected one that had drifted: an Unlock's `LOCKOUT_LIFT` reported the three lockout
paths whatever the Unlock did, and never the dormancy basis it restarts. It now
records the failure run when the run held attempts, and — when a lock stood —
`lockedAt`, `lockCause` and `lastAuthenticatedAt`.

ADRs 0013 and 0014 name `AuditTrail.recordLoginRefusal`, the method of the time; it
is now the `Audited.LoginRefusal` event, recorded under the same terms.

## Addendum (2026-10-13): Fail-open appends commit on a pool of their own

Architecture review 2026-10-11 (second pass) suspected, and the investigation
confirmed, that the fail-open path could starve the connection pool. A fail-open
append ran `PROPAGATION_REQUIRES_NEW` on the main JPA transaction manager, and
`REQUIRES_NEW` keeps the caller's connection checked out while it opens another,
so every fail-open append inside an open transaction needed a **second**
connection from the same pool. A refusal is recorded while its caller holds one:
`LoginAttemptService.recordFailure` holds its connection and the User row's lock
(the failure run's `UPDATE`) while it appends `LOGIN_FAILURE` and `LOCKOUT_SET`,
and the password change and the SCIM User and Group writes append their refusals
while holding write locks. With Hikari's defaults (10 connections, 30 s) and
Tomcat's 200 threads, ten overlapping failed Logins — reachable before sign-in —
held the whole pool, each waited 30 s for a connection none of them would give
back, the `CannotCreateTransactionException` was swallowed by the fail-open path
(the event lost behind an alert), and every other request failed meanwhile.
`audit_events` has no foreign keys, so the append could not self-deadlock; it
could only wait.

**Decision: fail-open appends — `Audited.Refused` and `Audited.Committed` — commit
on a dedicated, small audit append pool**, and never take a connection from the
main pool:

- The pool (`AuditAppendPoolConfig`) is two connections by default, with a 2 s
  connection timeout, both configurable (`APP_AUDIT_POOL_SIZE`,
  `APP_AUDIT_POOL_TIMEOUT`; `backend/README.md`). The size is bounded to two or
  three: a configuration outside that fails startup. It has no credentials of its
  own: it reads the main pool's URL, username and password off the main pool, so
  an append runs as the same login as every other statement — the app login,
  since #66 (it copied `connection-init-sql` and so `SET ROLE backend_app` until
  then; see the superseding note under Status).
- An append is one JDBC `INSERT` on an auto-committing connection
  (`IsolatedAuditEventPersistenceAdapter`, behind the `IsolatedAuditEventRepository`
  port): a transaction of its own by construction, with no transaction manager to
  join. It commits independently of the caller exactly as `REQUIRES_NEW` did, so a
  caller that rolls back keeps its refusal on record, and the caller's commit
  cannot carry it.
- The pool's beans are not default candidates, so Boot's auto-configuration still
  creates the main pool and the one, primary, JPA transaction manager, and every
  other bean is still given the main pool.
- **Fail-closed (`Audited.Accepted`) appends are unchanged**: they join the
  caller's transaction on the main pool, so a change this service cannot account
  for still does not happen.

The semantics are unchanged: a fail-open append still commits independently of
its caller, and a failure — a pool that cannot hand out a connection within its
timeout included — still raises `OperationalAlerts.auditAppendFailed` and is
swallowed. `AuditTrailServiceTests.everyKindOfEventFailsAsAdr0004Decides` holds
the axis kind by kind; `AuditAppendPoolStarvationIntegrationTests` holds the fix:
with every main-pool connection held by a transaction that used it, a fail-open
append from inside one of them completes well inside the main pool's timeout and
its event is in the trail. It failed before the change, the append waiting out the
main pool's timeout and the event lost.

**Rejected alternatives.** Deferring the append to after completion keeps the
caller's connection checked out all the same — the wait just moves. A
`PROPAGATION_NESTED` savepoint shares the caller's connection, but loses the event
when the caller's transaction rolls back, which is the fail-open guarantee.
A bigger main pool only moves the threshold the attack has to reach.

The cost: two more connections per instance against the database's connection
limit, and a second place a database outage is felt — a fail-open append fails
after 2 s rather than 30 s, and the request it rides answers sooner for it.

## Addendum (2026-10-13): A refused SCIM write is recorded once, at the edge

Architecture review 2026-10-13 (third pass), candidate B14. The SCIM use cases
recorded their own refusals, each in a `catch` that appended the refused event and
rethrew, while the same `scimType` raised by the adapter reading the request — a
PATCH naming a read-only attribute, a `name` that is not a complex value — was never
recorded at all. Whether a refusal reached the trail depended on which module
noticed it.

**Decision: a refused SCIM write is recorded by what it is, once, at the edge of the
request.** The SCIM controller advice (`ScimExceptionHandler`) records it from one
table (`scim.controller.ScimRefusals`): each kind of refusal names its status, its
`scimType`, its log reason and its audit reason, and the route the dispatcher matched
names the refused event (`ScimUserCreateRejected`, `ScimUserWriteRejected`,
`ScimUserDeleteRejected`, `ScimGroupCreateRejected`, `ScimGroupWriteRejected` — a
Group deletion stays a refused Group write). The use cases raise and never catch to
record. The deliberate exception stands: a refused precondition is not recorded.

The semantics this ADR decides are unchanged — every one of those events is
`Audited.Refused`, fail-open with an alert — and so is the fail-open path: the edge
appends through `AuditTrail` like every other caller. One consequence for the
starvation analysis above: a SCIM write's refusal is now appended after its
transaction has rolled back and released its connection and its write locks, so it
no longer holds a main-pool connection while it waits for the audit append pool.
