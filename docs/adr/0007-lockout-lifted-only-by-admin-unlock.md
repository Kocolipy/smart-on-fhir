# 7. A Lockout has two causes, failures and dormancy, and only an Admin's Unlock lifts it

Date: 2026-10-01

## Status

Accepted. This file states the current position: the permanent failure lockout
(2026-10-01), a departure from the original authentication slice, which shipped a
lockout that lifted on a timer; and the dormancy lockout and Membership
revocation (2026-10-03), a second cause under the same rule. Last revised
2026-10-23: a Login racing a Lockout no longer lifts it. History is in git.

## Context

### A lock that lifts on a timer

The original authentication slice specified the standard values: a lock after 5
failed attempts in a row, lifted automatically after 20 minutes, with a
configurable duration.

A lock that lifts on a timer allows a slow guessing rate: one burst of attempts
per window, indefinitely, and nobody is required to look. Once a lock instead
needs a human to lift it, two other things become necessary. A lock must also end
the sessions the User already holds, or an attacker holding one is unaffected.
And the recovery identity cannot be lockable, or an unauthenticated caller could
brick the deployment.

### Dormancy

Two jobs once enforced dormancy. Inactivity deactivation set `active=false` at 90
days and took priority over the directory, so the application overwrote an
attribute the identity provider is meant to own. Dormant-authority revocation
removed direct Admin-group membership at 180 days. Under ADR 0015 any Group may
carry Permissions, not only the Admin group.

The standalone App-Standard asks for disablement at 90 days and role revocation
at 180, and its recipe says "a user inactive for 180 days loses all roles". It
also asks that an unexpected mass removal can be reversed by hand. A deletion
step, which was considered first, could not be reversed and conflicted with the
standard's soft-delete and tombstone requirements.

## Decision

### The failure lockout

- 3 consecutive failures lock the User (`app.auth.lockout.max-attempts`,
  `APP_LOCKOUT_MAX_ATTEMPTS`). No duration exists, no configuration key expresses
  one, and the passage of time never lifts the lock. `locked_at` records when the
  lock was imposed, and `lock_cause` records `FAILURES`.
- The Bootstrap Admin never locks. Its failures are counted and audited.
- **Counting is atomic** (architecture review 2026-10-13, B18).
  `LoginStateService` serializes every login-state write itself: it takes the
  User's resource lock and decides from the state re-read under it, so callers do
  not decide whether to lock. The run is capped at the limit — attempts against a
  locked User are not counted — so N parallel failures at limit − 1 end with
  exactly one Lockout, one `LOCKOUT_SET`, one revocation, and
  `failed_login_attempts` equal to the limit. The Bootstrap Admin's run is
  serialized the same way. `LoginStateSerializationIntegrationTests` proves it
  against a real database.
- **Refusal timing is uniform** (architecture review 2026-10-08, T1). Spring
  Security's account-status check runs before the password is compared, so a
  locked or deactivated User could be refused faster than a wrong password.
  `SecurityConfig` sets `DaoAuthenticationProvider.alwaysPerformAdditionalChecksOnUser`:
  the submitted password is compared first and the account's state still refuses
  it, the right password included, with the same bare `401`. A wrong password, an
  unknown name, a credentialless User, a locked User and a deactivated User each
  cost exactly one Argon2id verification; `RefusalTimingEquivalenceTests` counts
  them. A credentialless User has no password Login: it is refused as an unknown
  name is, against Spring Security's own dummy hash rather than a stand-in hash a
  submitted string could match, audited as `BAD_CREDENTIALS` naming it, and
  counted toward no failure run, having no password to guess. The refusal's audit reason is `ACCOUNT_LOCKED` or `ACCOUNT_DISABLED`,
  whatever the password.
- **A Login racing a Lockout does not lift it.** Spring Security's account check
  reads the User without the login-state lock, before the Argon2id comparison, so
  a Lockout committed during the comparison would get past it.
  `LoginStateService.credentialVerified` re-checks the standing it reads under the
  User's lock: a User found locked, deactivated or gone is refused, fail closed,
  and nothing is written. The Login is answered and recorded as the refusal the
  account check would have made — `ACCOUNT_LOCKED` (counting nothing, as the run
  is capped) or `ACCOUNT_DISABLED` (counted, as that refusal always is) — and an
  Epic Login is refused the same way. ADR 0002's accepted window, a Login whose
  decision committed before a revocation, is unchanged.

### The dormancy lockout and Membership revocation

- **One dormancy job**, daily at 04:00 `Asia/Singapore`, on its own scheduled job
  lock row (ADR 0005). It applies to every User except the Bootstrap Admin and
  is measured from the dormancy basis.
- **Lockout at the lockout window** (`APP_DORMANCY_LOCKOUT_WINDOW`, 90 days). The
  User is locked with `lock_cause=DORMANCY` beside `locked_at`, and its failure run
  is untouched. An existing cause is never overwritten.
- **The lock stands in for "disable".** A locked User cannot sign in and has no
  sessions. `active` stays owned by the directory, and the application writes it
  under no job.
- **Membership revocation at its window**
  (`APP_DORMANCY_MEMBERSHIP_REVOCATION_WINDOW`, 180 days). The User's direct
  membership of every Group is removed — the Admin group's included, a Group
  conferring nothing included — each Group's version and the User's advance, and
  its sessions are revoked after commit. With Groups carrying Permissions, a
  Group's membership is the role, and a membership the job left alone could be
  given Permissions later. A connector may re-add a membership, and the next run
  removes it again while the User stays dormant. The Bootstrap Admin stays exempt
  and its Admin-group membership frozen.
- Startup fails on a non-positive window, or on a membership revocation window
  that is not longer than the lockout window.
- Audit records an actorless `DORMANCY_LOCKOUT`, and a
  `DORMANCY_MEMBERSHIP_REVOCATION` per User listing every removed Group id and the
  User's effective Permissions before and after, with the Superuser marker — the
  record that makes an unexpected mass removal reversible by hand. The
  revocation's session cause is `MEMBERSHIP_REVOKED`. The job uses the
  scheduled-job logging and metrics (ADR 0003, "Dormancy"). The lockout is logged
  at `WARN`, not `ERROR`, because it signals inactivity rather than an attack.

### Every cause

- Imposing a Lockout revokes the User's live sessions after commit (ADR 0002).
- The login refusal is the same bare `401` for every cause.
- **An administrator's Unlock is the only exit**, and it requires
  `account:unlock` (ADR 0015). It clears the failure run, resets the dormancy
  basis, and sets the change-required flag for a User that holds a password: the
  credential that hit the threshold may be the one being guessed. Every
  `LOCKOUT_LIFT` audit event therefore names the administrator. For dormancy this
  is a conscious deviation from the standard's 20-minute automatic lift, which was
  written for failure lockouts: a dormancy lock that expired would undo the
  control.
- **Unlock acts only on a locked User.** An Unlock of a User serving no lockout is
  refused `409`, writes nothing — its failure run is kept as evidence — revokes
  nothing, and is audited as a `LOCKOUT_LIFT` failure with reason `NOT_LOCKED`
  (ADR 0015, "Unlock acts only on a locked User").
- No administrator can unlock their own account; the self-target refusal is
  checked first. Recovering from a self-inflicted lock takes a second
  administrator or the Bootstrap Admin. Any other User may be unlocked, Superusers
  included: there is no rule refusing a target that holds more than the caller.
- **One login-state module decides every write** (issue #43). The login-state
  module, `LoginStateService`, decides what a failure, a dormancy lock, a
  Reactivation (SCIM `PUT` and `PATCH` alike) and an Unlock write. The dormancy job
  reports each due User to it, and it stores the lock through a set-based update
  that keeps its `locked_at is null` condition as a compare-and-set, so a cause
  that already stands is never overwritten. Neither the persistence adapter nor
  its queries hold any of these rules.

## Consequences

- A locked User learns nothing by waiting, by design: the refusal is the same
  bare `401` as a wrong password, in the same time, and the operational answer is
  an administrator.
- A guessing campaign spread across many accounts locks each of them, and
  unlocking them is administrator work. `LoginAuthenticationFailuresSustained`
  exists to catch that early, and edge throttling on Login caps its rate
  (`infra/docs/edge-throttling.md`).
- The Bootstrap Admin accepts unbounded online guessing. Argon2id cost, uniform
  refusal timing and audited failures mitigate it, not a lock.
- With the default windows, a dormant User is locked at 90 days and, if nobody
  unlocks it, loses every Group membership at 180. Bringing such a User back takes
  an administrator's Unlock, and getting its memberships back takes the directory
  re-adding them.
- A helpdesk operator can tell a forgotten password from an abandoned account
  before unlocking, from the lock cause in the account list.
- Nothing is deleted by the application. Deprovisioning stays with the directory
  through SCIM `DELETE`.
- ADR 0008's bound on an unreplaced imposed credential is enforced by the dormancy
  lockout at 90 days, and the Bootstrap Admin keeps its exemption.
- Security-event notifications are not delivered anywhere but the audit stream
  (App-Standards USR-8).
