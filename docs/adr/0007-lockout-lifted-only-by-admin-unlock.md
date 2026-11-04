---
type: Decision Record
title: "ADR 0007: Lockout, Unlock and the change-required flag"
description: A Lockout has two causes, consecutive failures and dormancy, never lifts on a timer, and only an administrator's Unlock of a locked User ends it; dormancy also revokes every Group membership; an imposed password has no grace deadline, and its flag confines a password Login only.
tags: [authentication, lockout, dormancy, unlock, passwords, change-required]
---

# 7. Lockout, Unlock and the change-required flag

Date: 2026-10-01

## Status

Accepted.

## Context

A lock that lifts on a timer allows a slow guessing rate: one burst of attempts
per window, indefinitely, and nobody is required to look. A lock that needs a
human to lift it makes two more things necessary: it must end the sessions the
User already holds, or an attacker holding one is unaffected; and the recovery
identity cannot be lockable, or an unauthenticated caller could brick the
deployment.

The standalone App-Standard asks for disablement at 90 days of dormancy and role
revocation at 180 — its recipe says "a user inactive for 180 days loses all
roles" — and that an unexpected mass removal can be reversed by hand. Under
ADR 0015 any Group may carry Permissions, so a Group's membership is the role.
`active` belongs to the identity provider.

IM8 ac-6 and as-15 ask for a change-required flag on every path that imposes a
credential, a session confined to change-password and logout, and clearing only
on a successful change. No control asks for a deadline on that change.

## Decision

### The failure lockout

- 3 consecutive failures lock the User (`app.auth.lockout.max-attempts`,
  `APP_LOCKOUT_MAX_ATTEMPTS`). No duration exists, no configuration key expresses
  one, and the passage of time never lifts the lock. `locked_at` records when the
  lock was imposed, and `lock_cause` records `FAILURES`.
- The Bootstrap Admin never locks. Its failures are counted and audited.
- **Counting is atomic.** `LoginStateService` serializes every login-state write
  itself: it takes the User's resource lock and decides from the state re-read
  under it, so callers do not decide whether to lock. The run is capped at the
  limit — attempts against a locked User are not counted — so N parallel failures
  at limit − 1 end with exactly one Lockout, one `LOCKOUT_SET`, one revocation,
  and `failed_login_attempts` equal to the limit. The Bootstrap Admin's run is
  serialized the same way. `LoginStateSerializationIntegrationTests` proves it
  against a real database.
- **A Login refused for a Lockout is never counted.** `LoginStateService` is
  handed the refusal's reason, and an `ACCOUNT_LOCKED` refusal counts nothing on
  that reason alone, whatever the state re-read under the lock holds: an
  Unlock can commit between the refusal and its recording, and counting it then
  would make an attempt against a Lockout the first failure of the run the
  Unlock started. It is still audited as a `LOGIN_FAILURE` with reason
  `ACCOUNT_LOCKED`, moving nothing. Every other reason is counted on the
  re-read state as above.
- **Refusal timing is uniform.** Spring Security's account-status check runs
  before the password is compared, so a locked or deactivated User could be
  refused faster than a wrong password.
  `SecurityConfig` sets `DaoAuthenticationProvider.alwaysPerformAdditionalChecksOnUser`:
  the submitted password is compared first and the account's state still refuses
  it, the right password included, with the same bare `401`, audited as
  `ACCOUNT_LOCKED` or `ACCOUNT_DISABLED` whatever the password. A wrong
  password, an unknown name, a credentialless User, a locked User and a
  deactivated User each cost exactly one Argon2id verification;
  `RefusalTimingEquivalenceTests` counts them.
- **A User with no password has no password Login.** It is refused as an
  unknown name is, against Spring Security's own dummy hash, which no submitted
  string matches; audited as `BAD_CREDENTIALS` naming it; and counted toward no
  failure run, by a Login or by the password change, having no password to
  guess. So attempts never lock out a User who signs in only through Epic.
- **A Login racing a Lockout does not lift it.** Spring Security's account check
  reads the User without the login-state lock, before the Argon2id comparison, so
  a Lockout committed during the comparison would get past it.
  The login-state module's Login entry points (`LoginStateService.passwordLogin`,
  `LoginStateService.epicLogin`) re-check the standing they read under the
  User's lock: a User found locked or deactivated is refused, fail closed, and
  nothing is written. The Login is answered and recorded as the refusal the
  account check would have made — `ACCOUNT_LOCKED` (counting nothing, on its
  reason, an Unlock committed in between included) or `ACCOUNT_DISABLED`
  (counted, as that refusal always is) — and an Epic Login is refused the same
  way. ADR 0002's accepted window, a Login whose decision committed before a
  revocation, is unchanged.

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
  scheduled-job logging and metrics (`/backend/docs/LOG_RECORDS.md`, "Dormancy"). The lockout is logged
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
  refused `409` with the generic `request-refused` `ApiError`, writes nothing —
  its failure run is kept as evidence — revokes nothing, and is audited and
  logged as a `LOCKOUT_LIFT` failure with reason `NOT_LOCKED`. The Bootstrap
  Admin, which never locks, is always refused so. Whether a lock stood is decided
  on the state read under the User's lock, so two racing Unlocks land as one
  success and one refusal. This is what keeps `account:unlock` delegatable (ADR
  0015, "Unlock cannot escalate").
- No administrator can unlock their own account; the self-target refusal is
  checked first. Recovering from a self-inflicted lock takes a second
  administrator or the Bootstrap Admin. Any other User may be unlocked, Superusers
  included: there is no rule refusing a target that holds more than the caller.
- **One login-state module decides every write.** `LoginStateService` decides what
  a failure, a dormancy lock, a Reactivation (SCIM `PUT` and `PATCH` alike) and an
  Unlock write. The dormancy job reports each due User to it, and it stores the
  lock through a set-based update that keeps its `locked_at is null` condition as
  a compare-and-set, so a cause that already stands is never overwritten. Neither
  the persistence adapter nor its queries hold any of these rules.

### The change-required flag

- **No grace deadline.** An imposed password that is never replaced is bounded
  by the dormancy lockout, measured from the last real use, so one job owns
  "this User is not using the account".
- **What sets the flag.** A connector setting a password — at creation, by `PUT`
  or by `PATCH` — which also ends the User's sessions, an Epic one included; an
  Unlock of a locked User, which revokes nothing itself, a locked User holding
  none; a Reactivation of a User with a password; and seeding the Bootstrap
  Admin. No administrative action sets it on a User that is not locked. Only a
  successful self-service change clears it.
  `password_change_required_since` holds it: its presence is the flag, and its
  value says when the change was last required.
- **Only a password Login is confined by the flag.** A password Login made while
  the flag is set gets a confined session, limited to the change and logout. It
  does not move the dormancy basis, since a confined session is no real use of
  the account; its failure run still clears, and `LOGIN_SUCCESS` is still
  audited.
- **An Epic Login is not confined.** It presents no password of ours, so confining
  it protects nothing. An Epic Login of a flagged User receives the authorities a
  password Login gives the same User with the flag clear — `ROLE_USER`, the
  baseline Permission and its Groups' Permissions — and `GET /api/auth/me`
  reports `passwordChangeRequired: false` for it, since the field reports the
  session's confinement. The login-state module decides it, under the User's
  lock: `LoginStateService.passwordLogin` confines a Login by a flagged User, and
  `epicLogin` never does, each reading the session's authorities there. An Epic
  Login neither reads the flag into the session nor clears it, and it moves the
  dormancy basis, being real use of the account.
- A successful self-service change records the authentication, since it is the
  account's first real use.
- One session per User holds across both Login paths.

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
- An imposed credential that is never replaced is locked out with the account
  at 90 days without real use. The Bootstrap Admin is seeded flagged and is exempt
  from the dormancy job, so an unchanged recovery credential never removes the
  recovery path; it never signs in through Epic (ADR 0012, D6), so it stays
  confined until it is changed.
- A User suspected compromised is handled in the directory: a new password, or a
  deactivation (ADR 0015, "Accepted cost").
- Security-event notifications are not delivered anywhere but the audit stream
  (App-Standards USR-8).

### Accepted risk

A flagged User who only ever signs in through Epic never has to replace the
imposed password, and keeps it live: its dormancy clock moves with each Epic
Login, so the dormancy lockout does not bound the imposed credential's life. The
imposed password still buys only a confined session, so the exposure is limited to
someone who knows it being able to set a new password for this User. That is
accepted: the credential is imposed by an administrator or a connector, never
chosen by the User, and an Epic-linked User suspected compromised is deactivated
rather than flagged (ADR 0012, D20).

## Alternatives considered

**The standard values: 5 failures, lifted after 20 minutes, duration
configurable.** Rejected for the slow-guessing rate above.

**Deactivating dormant Users** (`active=false` at 90 days) **and removing only
Admin-group membership at 180.** Rejected: the application would overwrite an
attribute the identity provider owns, and once any Group carries Permissions a
membership left alone could be given Permissions later.

**Deleting dormant Users.** Rejected: it cannot be reversed by hand and conflicts
with the standard's soft-delete and tombstone requirements.

**A grace deadline on the change**, a job deactivating a User still flagged past
a configured window. Rejected: it overlapped the dormancy lockout, and while every
confined Login moved the dormancy basis a flagged User who kept logging in
without changing the password never went dormant.

**Confining every Login.** Rejected: a clinician launching from Epic after an
Unlock or a connector password write could then do nothing but change a password
Epic never used (ADR 0012, D20).
