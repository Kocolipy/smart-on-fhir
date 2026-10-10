# 8. No password-change grace period; confined logins keep the dormancy clock

Date: 2026-10-01

## Status

Accepted. Records a departure from the original design that spans the
password-change lifecycle, which shipped the grace period, inactivity governance,
and the change-required confinement. ADR 0011 has since replaced the inactivity job with the
dormancy lockout; the consequences below are stated against it. Refined by the
2026-10-09 addendum below (#23): only a password Login is confined by the
change-required flag, so an Epic Login neither is confined nor keeps the
dormancy clock still.

## Context

The password-change lifecycle shipped a grace period. A User whose change-required flag stayed set past a
configured window was deactivated by a scheduled job,
`PASSWORD_CHANGE_GRACE_DEACTIVATION`. Separately, every accepted Login set
`lastAuthenticatedAt`, the dormancy basis that the inactivity job measured from.

The two rules together had a gap and an overlap. The gap: a flagged User who
kept logging in without changing the password never went dormant, because those
logins moved the clock, even though a confined session can do nothing except
change the password or log out. The overlap: the grace job deactivated Users the
inactivity job would also reach. A review against IM8 found no control that asks
for a grace deadline. ac-6 and as-15 ask for the flag on every path that imposes
a credential, a session confined to change-password and logout, and clearing
only on a successful change. The confinement work had already delivered all of
that.

## Decision

- No grace period exists. The grace job, its schedule, its lock row, its
  configuration key and its audit operation are removed, and the schema no
  longer writes the lock row.
- A Login made while the flag is set does not move `lastAuthenticatedAt`. Its
  failure run still clears, and `LOGIN_SUCCESS` is still audited.
- A successful self-service change records the authentication, since it is the
  account's first real use.
- `password_change_required_since` stays. Its presence is the flag, and its value
  says when the change was last required.

## Consequences

- An imposed credential that is never replaced is bounded by the dormancy
  lockout, 90 days by default, measured from the last real use, instead of by a
  separate deadline. One job owns "this User is not using the account".
- The Bootstrap Admin is seeded flagged and is exempt from the dormancy job, so
  an unchanged recovery credential never removes the recovery path. It stays
  confined until it is changed.
- Session revocation has one fewer trigger, and the grace job no longer holds a
  scheduler thread.

## Addendum (2026-10-09): only a password Login is confined by the flag (#23)

_Contradicts this ADR's "confined logins keep the dormancy clock" as written for
every Login, and ADR 0012's ac-6 note that "the session stays confined until the
User replaces it", but worth reopening because an Epic Login does not use the
imposed credential, so confining it protects nothing._

### Context

Until #23 a User whose change-required flag was set got a confined session from
every Login, an Epic Login included (ADR 0013). A clinician who launched from
Epic after an Unlock, a forced password change or a connector password write
landed on `/change-password` and could do nothing else, although Epic, not this
service, had checked the credential they presented. An Epic Login presents no
password of ours (ADR 0013, D20): the imposed credential the flag marks is not
what it used.

### Decision

- **Only a password Login is confined by the flag.** An Epic Login of a flagged
  User receives the authorities a password Login gives the same User with the
  flag clear: `ROLE_USER`, the baseline Permissions and its Role mapping
  Permissions. Its session is not confined, and `GET /api/auth/me` reports
  `passwordChangeRequired: false` for it, since the field reports the session's
  confinement. `LoginIdentityService.loadEpicLinkedUser` builds the authorities
  ignoring the flag; `loadUserByUsername` keeps the check. Both report a locked or
  deactivated User identically.
- **The flag itself is untouched.** An Epic Login neither reads it into the
  session nor clears it; only a successful self-service change clears it. A
  password Login by the same User is still confined to the change and logout.
- **An unconfined Epic Login moves the dormancy basis** even while the flag is
  set: it is real use of the account. A confined password Login still does not,
  as this ADR decided.
- **Session revocation is unchanged.** A forced password change still ends every
  session the User holds, an Epic one included, and a lockout ends them before an
  Unlock (which revokes nothing itself, a locked User holding none). One session
  per User still holds across both Login paths.
- The Bootstrap Admin is unaffected: it never signs in through Epic (ADR 0013,
  D6), so its recovery credential stays confined until it is changed.

### Accepted risk

A flagged User who only ever signs in through Epic never has to replace the
imposed password, and keeps it live past what this ADR intended: its dormancy
clock now moves with each Epic Login, so the dormancy lockout no longer bounds
the imposed credential's life. The imposed password still buys only a confined
session — a password Login with it can do nothing but change the password or log
out — so the exposure is limited to someone who knows it being able to set a new
password for this User. That is accepted: the credential is imposed by an
administrator or a connector, never chosen by the User, and an Epic-linked User
suspected compromised is deactivated rather than flagged (ADR 0013, D20).
