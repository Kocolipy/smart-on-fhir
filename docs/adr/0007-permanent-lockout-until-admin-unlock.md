# 7. Lockout is permanent until an Admin unlocks it

Date: 2026-10-01

## Status

Accepted. Records a departure from the original authentication slice, which
shipped a lockout that lifted on a timer. A later change replaced it with a
permanent one, and the Accounts page's Unlock, the password-change lifecycle and
the change-required confinement build on that change. ADR 0011 adds a second cause, dormancy, under the
same rule that only an administrator's Unlock lifts a lock. ADR 0010 replaced the
Admin authority with Permissions: Unlock now requires `user:write`.

## Context

The original authentication slice specified the standard values: a lock after 5
failed attempts in a row, lifted automatically after 20 minutes. It shipped that,
with a configurable duration (`app.auth.lockout.duration`).

A lock that lifts on a timer allows a slow guessing rate: one burst of attempts
per window, indefinitely, and nobody is required to look. Once a lock instead
needs a human to lift it, two other things become necessary. A lock must also end
the sessions the User already holds, or an attacker holding one is unaffected.
And the recovery identity cannot be lockable, or an unauthenticated caller could
brick the deployment. The change reaches past the authentication slice: it shapes
what Unlock means on the Accounts page and what Unlock does to the credential.

## Decision

- 3 consecutive failures lock the User (`app.auth.lockout.max-attempts`,
  `APP_LOCKOUT_MAX_ATTEMPTS`; the default was 5 until 2026-10-02). No
  duration exists, no configuration key expresses one, and the passage of time
  never lifts the lock. `locked_at` records when the lock was imposed.
- An administrator's Unlock is the only exit, and it clears the failure run.
  Every `LOCKOUT_LIFT` audit event therefore names the administrator.
- Imposing the lockout revokes the User's live sessions after commit (ADR 0002).
- The Bootstrap Admin never locks. Its failures are counted and audited.
- Unlocking a User that has a password also sets the change-required flag.
  The credential that hit the threshold may be the one being guessed.
- No administrator can unlock their own account. Recovering from a
  self-inflicted lock takes a second administrator or the Bootstrap Admin.

## Consequences

- A locked User learns nothing by waiting, by design: the refusal is the same
  bare `401` as a wrong password, in the same time (the 2026-10-09 amendment),
  and the operational answer is an administrator.
- A guessing campaign spread across many accounts locks each of them, and
  unlocking them is administrator work. `LoginAuthenticationFailuresSustained` exists to
  catch that early, and edge throttling on Login caps its rate (`infra/README.md`).
- The Bootstrap Admin accepts unbounded online guessing. Argon2id cost, uniform
  refusal timing and audited failures mitigate it, not a lock.
- `LockoutHasNoDurationTests` proves that no deployed configuration file
  mentions a duration.

## Amendments

- 2026-10-09 (architecture review 2026-10-08, T1): uniform refusal timing now
  covers locked and deactivated Users too. They are refused by Spring Security's
  account-status check, which runs before the password is compared, so before
  this they could answer faster than a wrong password. `SecurityConfig` sets
  `DaoAuthenticationProvider.alwaysPerformAdditionalChecksOnUser` explicitly: the
  submitted password is compared first and the account's state still refuses
  it, the right password included, with the same bare `401`. A wrong password,
  an unknown name, a credentialless User, a locked User and a deactivated User
  each cost exactly one Argon2id verification; `RefusalTimingEquivalenceTests`
  counts them. The refusal's audit reason is unchanged: `ACCOUNT_LOCKED` or
  `ACCOUNT_DISABLED`, whatever the password.
