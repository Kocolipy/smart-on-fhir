# 7. A Lockout has two causes, failures and dormancy, and only an Admin's Unlock lifts it

Date: 2026-10-01

## Status

Accepted. Consolidated on 2026-10-17 from two decisions: the permanent failure
lockout (2026-10-01), a departure from the original authentication slice, which
shipped a lockout that lifted on a timer; and the dormancy lockout and Role
revocation (2026-10-03), which added a second cause under the same rule. The
Accounts page's Unlock, the password-change lifecycle (ADR 0008) and the
change-required confinement build on them. ADR 0010 replaced the Admin authority
with Permissions: Unlock required `user:write`, and since 2026-10-21 requires
`account:unlock`; since 2026-10-22 an Unlock of a User that is not locked is
refused (see Amendments).

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
brick the deployment. The change reaches past the authentication slice: it shapes
what Unlock means on the Accounts page and what Unlock does to the credential.

### Dormancy

Two jobs enforced dormancy before this decision. Inactivity deactivation set
`active=false` at 90 days and took priority over the directory, so the application
overwrote an attribute the identity provider is meant to own. Dormant-authority
revocation removed direct Admin-group membership at 180 days. Under ADR 0010 any
mapped Group confers authority, not only the Admin group.

The standalone App-Standard asks for disablement at 90 days and role revocation
at 180. It also asks that an unexpected mass removal can be reversed by hand. A
deletion step, which was considered first, could not be reversed and conflicted
with the standard's soft-delete and tombstone requirements.

## Decision

### The failure lockout

- 3 consecutive failures lock the User (`app.auth.lockout.max-attempts`,
  `APP_LOCKOUT_MAX_ATTEMPTS`). No duration exists, no configuration key expresses
  one, and the passage of time never lifts the lock. `locked_at` records when the
  lock was imposed, and `lock_cause` records `FAILURES`.
- The Bootstrap Admin never locks. Its failures are counted and audited.

### The dormancy lockout and Role revocation

- **One dormancy job**, daily at 04:00 `Asia/Singapore`, on its own scheduled job
  lock row (ADR 0005). It applies to every User except the Bootstrap Admin and
  is measured from the dormancy basis.
- **Lockout at the lockout window** (`APP_DORMANCY_LOCKOUT_WINDOW`, 90 days). The
  User is locked with `lock_cause=DORMANCY` beside `locked_at`, and its failure run
  is untouched. An existing cause is never overwritten.
- **The lock stands in for "disable".** A locked User cannot sign in and has no
  sessions. `active` stays owned by the directory, and the application writes it
  under no job.
- **Role revocation at the role-revocation window**
  (`APP_DORMANCY_ROLE_REVOCATION_WINDOW`, 180 days). The User's direct membership
  of every mapped Group is removed, each Group's version advances, and sessions
  are revoked after commit. Unmapped memberships are untouched. A connector may
  re-add a membership, and the next run removes it again while the User stays
  dormant.
- Startup fails on a non-positive window, or on a role-revocation window that is
  not longer than the lockout window.
- Audit records an actorless `DORMANCY_LOCKOUT` and `DORMANCY_ROLE_REVOCATION`. The
  job uses the scheduled-job logging and metrics: counts of Users locked and Users
  whose Roles were revoked, run outcome and duration. The lockout is logged at
  `WARN`, not `ERROR`, because it signals inactivity rather than an attack.

### Every cause

- Imposing a Lockout revokes the User's live sessions after commit (ADR 0002).
- The login refusal is the same bare `401` for every cause.
- **An administrator's Unlock is the only exit.** It clears the failure run, resets
  the dormancy basis, and sets the change-required flag for a User that holds a
  password: the credential that hit the threshold may be the one being guessed.
  Every `LOCKOUT_LIFT` audit event therefore names the administrator. For dormancy
  this is a conscious deviation from the standard's 20-minute automatic lift,
  which was written for failure lockouts: a dormancy lock that expired would undo
  the control.
- No administrator can unlock their own account. Recovering from a
  self-inflicted lock takes a second administrator or the Bootstrap Admin.
- **One login-state module decides every write** (issue #43). The login-state
  module, `LoginStateService`, decides what a failure, a dormancy lock, a
  Reactivation (SCIM `PUT` and `PATCH` alike) and an Unlock write. The dormancy job
  reports each due User to it, and it stores the lock through a set-based update
  that keeps its `locked_at is null` condition as a compare-and-set, so a cause
  that already stands is never overwritten. Neither the persistence adapter nor
  its queries hold any of these rules.

## Consequences

- A locked User learns nothing by waiting, by design: the refusal is the same
  bare `401` as a wrong password, in the same time (the 2026-10-09 amendment),
  and the operational answer is an administrator.
- A guessing campaign spread across many accounts locks each of them, and
  unlocking them is administrator work. `LoginAuthenticationFailuresSustained` exists to
  catch that early, and edge throttling on Login caps its rate (`infra/docs/edge-throttling.md`).
- The Bootstrap Admin accepts unbounded online guessing. Argon2id cost, uniform
  refusal timing and audited failures mitigate it, not a lock.
- `LockoutHasNoDurationTests` proves that no deployed configuration file
  mentions a duration.
- With the default windows, a dormant User is locked at 90 days and, if nobody
  unlocks it, loses its Roles at 180. Bringing such a User back takes an
  administrator's Unlock, and getting its Roles back takes the directory
  re-adding its memberships.
- A helpdesk operator can tell a forgotten password from an abandoned account
  before unlocking, from the lock cause in the account list.
- Nothing is deleted by the application. Deprovisioning stays with the directory
  through SCIM `DELETE`.
- ADR 0008's bound on an unreplaced imposed credential is enforced by the dormancy
  lockout at 90 days, and the Bootstrap Admin keeps its exemption.
- Security-event notifications are not delivered anywhere but the audit stream
  (App-Standards USR-8).

## Amendments

- 2026-10-09 (architecture review 2026-10-08, T1): uniform refusal timing covers
  locked and deactivated Users too. They are refused by Spring Security's
  account-status check, which runs before the password is compared, so they could
  answer faster than a wrong password. `SecurityConfig` sets
  `DaoAuthenticationProvider.alwaysPerformAdditionalChecksOnUser` explicitly: the
  submitted password is compared first and the account's state still refuses
  it, the right password included, with the same bare `401`. A wrong password,
  an unknown name, a credentialless User, a locked User and a deactivated User
  each cost exactly one Argon2id verification; `RefusalTimingEquivalenceTests`
  counts them. The refusal's audit reason is `ACCOUNT_LOCKED` or
  `ACCOUNT_DISABLED`, whatever the password.
- 2026-10-13 (architecture review 2026-10-13, B18; spec C6): counting is atomic. A
  failure that computed the run from a read taken without a lock let parallel wrong
  guesses each read the same run: more guesses got through than the limit allows,
  each could impose and audit its own Lockout, and an Unlock racing a failure could
  be overwritten. `LoginStateService` serializes every login-state write itself: it
  takes the User's resource lock and decides from the state re-read under it, so
  callers do not decide whether to lock. The run is capped at the limit — attempts
  against a locked User are not counted — so N parallel failures at limit − 1 end
  with exactly one Lockout, one `LOCKOUT_SET`, one revocation, and
  `failed_login_attempts` equal to the limit. The Bootstrap Admin's run is
  serialized the same way and still never locks.
  `LoginStateSerializationIntegrationTests` proves it against a real database.
- 2026-10-21 (#157, ADR 0015): Unlock requires `account:unlock` (previously
  `user:write`), and so does the forced password change. `user:write` meant two
  different powers: over SCIM it sets any User's password, which is
  Superuser-equivalent, while on the session side it guarded only these two
  actions, which cannot escalate. `account:unlock` is the helpdesk Permission: a
  Group may carry it and a connector token may not, and `user:write` is now a
  token's alone. Who may be unlocked is unchanged: any User but the caller itself,
  Superusers included, and the forced change also refuses the Bootstrap Admin.
  There is no rule refusing a target that holds more than the caller. Refusals
  stay a generic `403`.
- 2026-10-21 (#156, ADR 0015): **dormancy revocation strips every Group
  membership**, previously role-mapped Groups only. Past the revocation window
  (`APP_DORMANCY_ROLE_REVOCATION_WINDOW`, 180 days, the variable keeping its name)
  the User's direct membership of every Group is removed — the Admin group's
  included, a Group conferring nothing included — each Group's version and the
  User's advance, and its sessions are revoked after commit as before. This
  follows the Standalone User Access Control App-Standard's "Revoke roles from
  long-inactive accounts" and its recipe's "a user inactive for 180 days loses all
  roles": with Groups carrying Permissions, a Group's membership is the role, and a
  membership the job left alone could be given Permissions later. The Bootstrap
  Admin stays exempt and its Admin-group membership frozen. The audit event becomes
  `DORMANCY_MEMBERSHIP_REVOCATION`: one actorless row per User listing every
  removed Group id and the User's effective Permissions before and after, with the
  Superuser marker — the record that makes an unexpected mass removal reversible
  by hand. The 90-day lockout and its behaviour are unchanged.
- 2026-10-22 (#158, ADR 0015): with Roles gone, the step and its settings take
  the glossary's name, **Membership revocation**. The window is
  `APP_DORMANCY_MEMBERSHIP_REVOCATION_WINDOW` (`app.dormancy.membership.revocation.window`);
  the old variable is no longer read. The startup fields, the `job-end` count
  `dormancy.memberships_revoked_count`, the counter
  `app.dormancy.users.memberships.revoked` and the session revocation cause
  `MEMBERSHIP_REVOKED` were renamed with it. Audit rows recorded before carry the
  cause as `ROLE_REVOKED` and are left as written. Windows, defaults and behaviour
  are unchanged.
- 2026-10-22 (ADR 0015's 2026-10-22 addendum): **Unlock acts only on a locked
  User, and the forced password change is removed.** An Unlock of a User serving
  no lockout used to be idempotent and cleared its failure run; it is now refused
  `409`, writes nothing — the run is kept as evidence — revokes nothing, and is
  audited as a `LOCKOUT_LIFT` failure with reason `NOT_LOCKED`. The self-target
  refusal is still checked first. The forced password change that the 2026-10-21
  amendment put under `account:unlock` beside Unlock is gone: a holder of
  `account:unlock` could repeat it on any Superuser but the Bootstrap Admin,
  ending their sessions each time. Unlock of a locked User — the only lift there
  is — is unchanged: the lock and the run cleared, the dormancy basis restarted
  and, for a User with a password, a change required. The accepted cost: no one
  can require a password change of an unlocked User whose password is suspected
  compromised. Recovery goes through the connector — the directory sets a new
  password, which sets the change-required flag and ends the User's sessions, or
  deactivates the User.
