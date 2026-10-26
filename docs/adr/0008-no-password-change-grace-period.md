# 8. No password-change grace period; confined logins keep the dormancy clock

Date: 2026-10-01

## Status

Accepted. Last revised 2026-10-22. History is in git.

## Context

The password-change lifecycle shipped a grace period. A User whose
change-required flag stayed set past a configured window was deactivated by a
scheduled job, `PASSWORD_CHANGE_GRACE_DEACTIVATION`. Separately, every accepted
Login set `lastAuthenticatedAt`, the dormancy basis that the inactivity job
measured from.

The two rules together had a gap and an overlap. The gap: a flagged User who
kept logging in without changing the password never went dormant, because those
logins moved the clock, even though a confined session can do nothing except
change the password or log out. The overlap: the grace job deactivated Users the
inactivity job would also reach. A review against IM8 found no control that asks
for a grace deadline. ac-6 and as-15 ask for the flag on every path that imposes
a credential, a session confined to change-password and logout, and clearing
only on a successful change. The confinement work had already delivered all of
that.

Confinement first applied to every Login. A clinician launching from Epic after
an Unlock or a connector password write could then do nothing but change a
password Epic never used (#23, ADR 0012, D20).

## Decision

- **No grace period exists.** The grace job, its schedule, its lock row, its
  configuration key and its audit operation are removed.
- **What sets the flag.** A connector password write, which also ends the User's
  sessions, an Epic one included; an Unlock of a locked User, which revokes
  nothing itself, a locked User holding none; a Reactivation of a User with a
  password; and seeding the Bootstrap Admin. No administrative action sets it on a
  User that is not locked. Only a successful self-service change clears it.
  `password_change_required_since` holds it: its presence is the flag, and its
  value says when the change was last required.
- **Only a password Login is confined by the flag.** A password Login made while
  the flag is set gets a confined session, limited to the change and logout. It
  does not move `lastAuthenticatedAt`; its failure run still clears, and
  `LOGIN_SUCCESS` is still audited.
- **An Epic Login is not confined.** It presents no password of ours, so confining
  it protects nothing. An Epic Login of a flagged User receives the authorities a
  password Login gives the same User with the flag clear — `ROLE_USER`, the
  baseline Permission and its Groups' Permissions — and `GET /api/auth/me`
  reports `passwordChangeRequired: false` for it, since the field reports the
  session's confinement. `LoginIdentityService.loadEpicLinkedUser` builds the
  authorities ignoring the flag; `loadUserByUsername` keeps the check. An Epic
  Login neither reads the flag into the session nor clears it, and it moves the
  dormancy basis, being real use of the account.
- A successful self-service change records the authentication, since it is the
  account's first real use.
- One session per User holds across both Login paths.

## Consequences

- An imposed credential that is never replaced is bounded by the dormancy
  lockout, 90 days by default, measured from the last real use, instead of by a
  separate deadline. One job owns "this User is not using the account".
- The Bootstrap Admin is seeded flagged and is exempt from the dormancy job, so
  an unchanged recovery credential never removes the recovery path. It never signs
  in through Epic (ADR 0012, D6), so it stays confined until it is changed.
- A User suspected compromised is handled in the directory: a new password, or a
  deactivation (ADR 0015, "Accepted cost").

### Accepted risk

A flagged User who only ever signs in through Epic never has to replace the
imposed password, and keeps it live: its dormancy clock moves with each Epic
Login, so the dormancy lockout does not bound the imposed credential's life. The
imposed password still buys only a confined session, so the exposure is limited to
someone who knows it being able to set a new password for this User. That is
accepted: the credential is imposed by an administrator or a connector, never
chosen by the User, and an Epic-linked User suspected compromised is deactivated
rather than flagged (ADR 0012, D20).
