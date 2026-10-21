---
type: Domain Rules
title: Dormancy and scheduled jobs
description: The dormancy basis and windows, the dormancy job's lockout and membership revocation, the audit retention job, and how scheduled jobs are serialized.
tags: [dormancy, scheduled-jobs, retention]
---

# Dormancy and scheduled jobs

**Dormancy basis** — the instant a User's dormancy is measured from: its
`lastAuthenticatedAt` — set by every successful Login whose session is not
confined (a password Login made while no password change is required, and every
Epic Login), by a completed self-service change, by an explicit reactivation and
by an administrator's Unlock of a lock — or, for a User that has had none of
those, its creation time. The fallback keeps a User provisioned without a password
from being dormant the moment it exists. A confined session's Login does not move
it, so a credential imposed on a User that is never replaced still ages into the
dormancy lockout. Application-owned authentication state, like the failure run:
not a SCIM attribute, absent from `/Schemas`, and writing it moves no version.

**Dormant User** — a User whose dormancy basis is further in the past than a
dormancy window. Dormancy is relative to a window, not a stored state: the same
User can be dormant for the lockout (90 days by default,
`APP_DORMANCY_LOCKOUT_WINDOW`) and not yet for membership revocation (180 days,
`APP_DORMANCY_ROLE_REVOCATION_WINDOW`, which keeps its name). Startup fails on a
non-positive window, or on a revocation window not longer than the lockout window. The Bootstrap
Admin is never treated as dormant, by its reservation marker, for the reason it is
exempt from lockout.

## Dormancy job

The scheduled job that enforces dormancy (ADR 0007), daily at 04:00
`Asia/Singapore` on its own scheduled job lock row. What it owes each User is that
User's **dormancy verdict** — not due, Lockout, or Lockout + Membership revocation —
which owns the basis choice and both cutoffs, answers not due for the Bootstrap
Admin, and is the only place the job compares a basis with a window. Two steps:

- **Membership revocation.** A User past the revocation window — active or not,
  locked or not — loses its direct membership of **every** Group: the Admin
  group's included, and a Group that confers nothing included (ADR 0007,
  2026-10-21 addendum; "a user inactive for 180 days loses all roles"). Each
  affected Group's version and the User's advance, the User's sessions are revoked
  after commit, and one actorless `DORMANCY_MEMBERSHIP_REVOCATION` event names the
  User, lists every Group removed by id, and records the User's effective
  Permissions before and after with the Superuser marker — the record an
  unexpected mass removal is reversed from by hand. A connector may re-add a
  membership; while the User stays dormant the next run removes it again.
- **Lockout.** An unlocked User past the lockout window — active or not — is
  locked with lock cause `DORMANCY`, its sessions revoked after commit, and an
  actorless `DORMANCY_LOCKOUT` event recorded and logged at `WARN` (inactivity,
  not an attack). A User already locked keeps its lock and its cause. The lock
  stands in for "disable": it never lifts on its own — a conscious deviation from
  the standard's 20-minute automatic lift, which is written for failure lockouts —
  and only an administrator's Unlock ends it.

The job never writes `active`, which stays the directory's, and never deletes:
deprovisioning stays with SCIM `DELETE`. A connector re-asserting `active=true`
resets nothing and lifts no lock. The Bootstrap Admin is exempt from both steps.
Its runs are counted (`app.job.runs{job="dormancy"}`) and so are their changes
(`app.dormancy.users.locked`, and `app.dormancy.users.roles.revoked` counting Users
whose memberships were revoked), so a mass lockout or revocation shows as a
spike. With the development fixtures on (`APP_DEV_FIXTURES_ENABLED=true`), the job
also runs once at startup, so the `dormant` fixture — backdated past the
revocation window and put back into the Groups `dormant-groups` names — is locked
and stripped of those memberships without waiting for 04:00.

## Audit retention job

The second scheduled job, daily at 03:30 by default on its own scheduled job lock
row, deleting every audit event older than the retention period: 365 days unless
deployment configuration says otherwise, and never less than 90. A configured
period below the floor stops startup rather than shortening the trail, because 90
days is the shortest window in which an investigation into a User's activity is
still possible. It deletes in batches of at most 4,000 events, each committed on
its own, so an audit append never waits behind a run, and a run that fails
part-way keeps what it deleted for the next run to finish; the job's lock is held
across every batch. It runs as the retention login, the one database principal
that may delete an audit event.

## Scheduled job lock

How the scheduled jobs are serialized: each run takes its own job's row in
`scheduled_job_locks` with `WITH (UPDLOCK, ROWLOCK, READPAST)` and holds it for the
run's own transaction, which lasts the whole run, every batch included (ADR 0005).
A second run of the same job, on any instance, skips; another job holds a
different row and never waits. Inside a run, each User is re-read under its
resource lock and decided again, so no User is processed twice.
