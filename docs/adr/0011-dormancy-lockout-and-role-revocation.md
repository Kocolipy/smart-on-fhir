# 11. Dormancy locks at 90 days and revokes Roles at 180; the application stops writing `active`

Date: 2026-10-03

## Status

Accepted. It supersedes the dormancy jobs that ADR 0008 relies on
(inactivity deactivation and dormant-authority revocation), and it extends ADR
0007's Lockout with a second cause.

## Context

Two jobs enforce dormancy today. Inactivity deactivation sets `active=false` at
90 days and takes priority over the directory, so the application overwrites an
attribute the identity provider is meant to own. Dormant-authority revocation
removes direct Admin-group membership at 180 days. Under ADR 0010 any mapped
Group confers authority, not only the Admin group.

The standalone App-Standard asks for disablement at 90 days and role revocation
at 180. It also asks that an unexpected mass removal can be reversed by hand. A
deletion step, which was considered first, could not be reversed and conflicted
with the standard's soft-delete and tombstone requirements.

## Decision

- **One dormancy job**, daily at 04:00 `Asia/Singapore`, on its own scheduled job
  lock row (ADR 0005). It applies to every User except the Bootstrap Admin and
  is measured from the dormancy basis.
- **Lockout at the lockout window** (`APP_DORMANCY_LOCKOUT_WINDOW`, 90 days). The
  User is locked with `lock_cause=DORMANCY` beside `locked_at`, and its sessions
  are revoked after commit. Failure lockouts record `FAILURES`, and an existing
  cause is never overwritten. The login refusal stays a bare `401` for every
  cause.
- **Only an administrator's Unlock lifts a dormancy lock.** Unlock needs
  `user:write`, resets the dormancy basis, and flags a password change for a
  User that holds a password, as Unlock already does. The lock never lifts on
  its own. This is a conscious deviation from the standard's 20-minute
  automatic lift, which was written for failure lockouts: a dormancy lock that
  expired would undo the control.
- **The lock stands in for "disable".** A locked User cannot sign in and has no
  sessions. `active` stays owned by the directory, and the application no longer
  writes it under any job.
- **Role revocation at the role-revocation window**
  (`APP_DORMANCY_ROLE_REVOCATION_WINDOW`, 180 days). The User's direct membership
  of every mapped Group is removed, each Group's version advances, and sessions
  are revoked after commit. Unmapped memberships are untouched. A connector may
  re-add a membership, and the next run removes it again while the User stays
  dormant.
- Startup fails on a non-positive window, or on a role-revocation window that is
  not longer than the lockout window.
- Audit records an actorless `DORMANCY_LOCKOUT` and `DORMANCY_ROLE_REVOCATION`,
  and no longer writes `INACTIVITY_DEACTIVATION`, `DORMANT_AUTHORITY_REVOCATION`
  or `ADMIN_MEMBERSHIP_REMOVED`. The job uses the existing scheduled-job logging
  and metrics: counts of Users locked and Users whose Roles were revoked, run
  outcome and duration. The lockout is logged at `WARN`, not `ERROR`, because it
  signals inactivity rather than an attack.

## Consequences

- With the default windows, a dormant User is locked at 90 days and, if nobody
  unlocks it, loses its Roles at 180. Bringing such a User back takes an
  administrator's Unlock, and getting its Roles back takes the directory
  re-adding its memberships.
- A helpdesk operator can tell a forgotten password from an abandoned account
  before unlocking, from the lock cause in the account list.
- Nothing is deleted by the application. Deprovisioning stays with the directory
  through SCIM `DELETE`.
- ADR 0008's bound on an unreplaced imposed credential still holds. It is now
  enforced by the dormancy lockout at 90 days rather than by deactivation, and
  the Bootstrap Admin keeps its exemption.
- Security-event notifications are not delivered anywhere but the audit stream
  (App-Standards USR-8).
