# 2. Revoke a User's sessions after the commit

Date: 2026-09-25

## Status

Accepted. Covers every write that ends a User's sessions.

## Context

Several writes must end a User's sessions: a Lockout imposed by
`LoginStateService` (ADR 0007), a dormancy lockout or Membership revocation, a
password change, a SCIM User or Group write, a Group's Permissions reduced (ADR
0015), and an accepted Login's one-session-per-User sweep. Each does two writes that live in different systems. The User's state is
written inside the service's transaction; the sessions are ended through the
`AccountSessions` port, which is backed by Spring Session over Redis and is
**not** in that transaction.

Originally the revocation was the last statement of the transactional method, and
the Javadoc reasoned that "the revocation happens after the write… once the write
is committed no further login can succeed, so the gap is one transaction wide".
The first clause held; the second described the **commit**, while the call ran
before it. Two consequences followed:

- A commit failure after the revocation left the sessions gone and the write
  undone. Redis cannot be rolled back, so the User read as unchanged while its
  holder had been signed out, and nothing recorded that this happened.
- The in-flight-login window straddled the commit rather than ending at it: a
  Login committing its session between the revocation and this transaction's
  commit still read the old state, and its session was not in the set revoked.

No test with an in-memory session registry and no transaction could tell the two
orderings apart.

## Decision

The revocation runs **after the transaction commits**, through an explicit
application-layer port. Every trigger goes through `SessionRevocationService`,
which schedules the revocation and its audit record on it:

```java
afterCommit.run(() -> revoke(actorId, userId, causesAtCall, end));
```

`AfterCommit` (`auth.application.AfterCommit`) says only _this belongs after the
commit_. `AfterCommitAdapter` registers a `TransactionSynchronization` on the
transaction bound to the calling thread, and runs the work immediately when no
transaction is bound — with no commit to wait for and no rollback that could
contradict it, "after the commit" and "now" are the same instant.

**One failed revocation does not stop the rest.** Spring runs a transaction's
synchronizations one after another and stops at the first that throws, so with
one synchronization per revocation a store failing on the first member of a Group
whose Permissions were reduced would leave every later member signed in, with no
audit row and no alert. `AfterCommitAdapter` therefore registers one
synchronization per transaction and queues every piece of work on it, in the
order handed over; it runs them all, then throws the first failure with each later
one suppressed on it. Each revocation is attempted, audited and, on failure,
alerted, and the request still fails as ADR 0004 ("Session revocation") decides.

## Consequences

- A rolled-back write revokes nothing: both halves stay as they were, and the
  User's state can no longer disagree with the sessions.
- The in-flight-login race is bounded by the commit instead of straddling it. It
  is not closed — a login that commits its session between the commit and the
  revocation keeps it. Closing it entirely needs a second sweep after commit, or a
  lock on the account; neither is judged worth it while the window is microseconds
  and a repeated write clears the result. The window stays that narrow because a
  Login reads what it would lose — its standing and the authority its session is
  issued with, Group memberships and Group Permissions included — under the
  User's lock in its decision transaction, after the password comparison (ADR
  0001), not when it first reads the User: a write committed during the
  comparison is in the session's authority, and one committing after that read
  finds the session once the Login has stored it.
- The failure mode is reversed, deliberately. If the revocation itself fails the
  write is durable while the sessions survive; ADR 0004 ("Session
  revocation") decides whether the error surfaces to the caller. That is the safer half to keep:
  a locked User can no longer log in, and repeating the write revokes again. Under
  the previous ordering the same failure rolled the write back entirely.
- The revocation runs outside the transaction and outside the persistence context,
  so it must not lazily load anything. It takes the account's id
  (`AccountSessions.revokeAll(UUID)`), which is why this is cheap.
- Testable: the `PendingCommit` double distinguishes the orderings — e.g.
  `LoginStateServiceTests.aRolledBackLockoutRevokesNothing` — and models the
  run-them-all guarantee; `AfterCommitAdapterTests` drives Spring's real
  synchronization registry, and `AfterCommitRevocationTests` holds the guarantee
  through a real transaction manager.

## Alternatives considered

**Leave it inside the transaction, best-effort.** Rejected: it was the
defect, and the divergence it allows is invisible — the operator sees `Active` and
the holder sees signed-out, with no record of the rollback.

**Revoke before the write.** Rejected: a refused or failed write would then end
sessions it had no right to end, which is the same divergence pointing the other
way and worse, because it happens on the guard paths that are supposed to change
nothing.

**A domain event with `@TransactionalEventListener(AFTER_COMMIT)`.** Same
ordering, and the framework-idiomatic route. Rejected for the reason ADR 0001
rejected the analogous listener: reading the write would no longer reveal that
sessions are revoked, and the indirection buys nothing while there is one
consumer.
