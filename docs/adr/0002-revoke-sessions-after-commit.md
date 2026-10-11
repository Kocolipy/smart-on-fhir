# 2. Revoke a disabled account's sessions after the commit

Date: 2026-09-25

## Status

Accepted.

The disable endpoint this ADR was written against has since been removed;
deactivation now comes only from the directory (SCIM). The decision stands and is
carried by the `AfterCommit` port (`auth.application.AfterCommit`): every service
that ends sessions as part of a write — a lockout imposed by `LoginStateService`, a
dormancy lockout, a password change, a SCIM User or Group write — revokes them
only once its transaction commits.

## Context

Disabling an account does two writes that live in different systems. The
`enabled` column is written inside `AccountAdministrationService.disable`'s
transaction; the sessions are ended through the `AccountSessions` port, which is
backed by Spring Session over Redis and is **not** in that transaction.

Originally the revocation was the last statement of the transactional method, and
the Javadoc reasoned that "the revocation happens after the write… once the write
is committed no further login can succeed, so the gap is one transaction wide".
The first clause held; the second described the **commit**, while the call ran
before it. Two consequences followed:

- A commit failure after the revocation left the sessions gone and the account
  still enabled. Redis cannot be rolled back, so the account read `Active` while
  its holder had been signed out, and nothing recorded that this happened.
- The in-flight-login window straddled the commit rather than ending at it: a
  login committing its session between `revokeAll` and this transaction's commit
  still read `enabled = true`, and its session was not in the set revoked.

No test could tell the two orderings apart. `AccountAdministrationServiceTests`
runs with an in-memory registry and no transaction, and `AdminAccountEndpointTests`
never fails a commit.

## Decision

The revocation runs **after the transaction commits**, through an explicit
application-layer port:

```java
AccountSummary disabled = applyEnabled(account, false);
afterCommit.run(() -> sessions.revokeAll(account.username()));
return disabled;
```

`AfterCommit` says only *this belongs after the commit*. `AfterCommitAdapter`
registers a `TransactionSynchronization` on the transaction bound to the calling
thread, and runs the work immediately when no transaction is bound — with no
commit to wait for and no rollback that could contradict it, "after the commit"
and "now" are the same instant.

The call site stays visible in `disable`, which is the same reason ADR 0001 gives
for recording login attempts on the login path rather than through a listener. A
`@TransactionalEventListener(AFTER_COMMIT)` would have achieved the same ordering
by publishing a domain event, at the price of a use case that no longer shows what
disabling does.

## Consequences

- A rolled-back disable revokes nothing: both halves stay as they were, and the
  listing can no longer disagree with the sessions.
- The in-flight-login race is bounded by the commit instead of straddling it. It
  is not closed — a login that commits its session between the commit and the
  revocation keeps it. Closing it entirely needs a second sweep after commit, or a
  lock on the account; neither is judged worth it while the window is microseconds
  and a repeated disable clears the result.
- The failure mode is reversed, deliberately. If the revocation itself fails the
  account is durably disabled while its sessions survive, and the error surfaces to
  the caller. That is the safer half to keep: the account can no longer log in, and
  repeating the disable writes nothing and revokes again. Under the previous
  ordering the same failure rolled the disable back entirely.
- The revocation runs outside the transaction and outside the persistence context,
  so it must not lazily load anything. It takes the account's id
  (`AccountSessions.revokeAll(UUID)`), which is why this is cheap.
- Testable: the `PendingCommit` double distinguishes the orderings — e.g.
  `LoginStateServiceTests.aRolledBackLockoutRevokesNothing` — and
  `AfterCommitAdapterTests` drives Spring's real synchronization registry.

## Alternatives considered

**Leave it inside the transaction, best-effort.** Rejected: it is the current
defect, and the divergence it allows is invisible — the operator sees `Active` and
the holder sees signed-out, with no record of the rollback.

**Revoke before the write.** Rejected: a refused or failed disable would then end
sessions it had no right to end, which is the same divergence pointing the other
way and worse, because it happens on the guard paths that are supposed to change
nothing.

**A domain event with `@TransactionalEventListener(AFTER_COMMIT)`.** Same
ordering, and the framework-idiomatic route. Rejected for the reason ADR 0001
rejected the analogous listener: reading `disable` would no longer reveal that
sessions are revoked, and the indirection buys nothing while there is one
consumer.
