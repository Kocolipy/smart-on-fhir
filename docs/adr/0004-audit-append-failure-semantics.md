# 4. Audit append failure semantics: fail-closed on a write, fail-open on a refusal

Date: 2026-09-25

## Status

Accepted.

The administrator's disable and enable named below were removed when `active`
became the directory's. The rule is unchanged and is the decision, not the list:
an event an otherwise-successful request records joins its transaction; refusal
events run fail-open, and so does the record of a post-commit session revocation,
whose transaction has already committed (`AuditTrailService`). How a failing
session store answers, on every revocation trigger, is the 2026-10-10 addendum below.

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
