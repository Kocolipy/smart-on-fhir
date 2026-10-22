---
type: Configuration Reference
title: Dormancy
description: The two dormancy windows, how the job is serialized and logged, and its startup run with the development fixtures.
tags: [dormancy, scheduled-jobs, configuration]
---

# Dormancy

| Variable                                    | Default           | Meaning                                                                                                 |
| ------------------------------------------- | ----------------- | ------------------------------------------------------------------------------------------------------- |
| `APP_DORMANCY_LOCKOUT_WINDOW`               | `90d` (90 days)   | How long a User may go without logging in before it is locked for dormancy                              |
| `APP_DORMANCY_MEMBERSHIP_REVOCATION_WINDOW` | `180d` (180 days) | How long before its direct membership of every Group is removed; must be longer than the lockout window |

> **Breaking change (#158).** The revocation window was configured as
> `APP_DORMANCY_ROLE_REVOCATION_WINDOW` (property
> `app.dormancy.role.revocation.window`). That name is no longer read: a deployment
> still setting it runs with the 180-day default, or fails startup if its lockout
> window is 180 days or longer. Rename the variable when upgrading. The telemetry
> names moved with it: the startup fields `app.dormancy.membership_revocation.window`
> and `dormancy.membership_revocation.window`, the `job-end` count
> `dormancy.memberships_revoked_count` (was `dormancy.roles_revoked_count`) and the
> counter `app.dormancy.users.memberships.revoked` (was
> `app.dormancy.users.roles.revoked`), so dashboards and alerts on the old names
> need updating too.

One daily job (04:00 `Asia/Singapore`) applies both windows, measured from each
User's dormancy basis. What a run does to a User — the lockout, the membership
revocation, and the Bootstrap Admin's exemption — is in `/docs/domain/dormancy.md`;
this file covers only running it.

The job is serialized on its own row in `scheduled_job_locks`
(`SELECT … WITH (UPDLOCK, ROWLOCK, READPAST)`, held for the run's transaction), so two runs
never overlap across instances and a run that finds the job already running skips.
A zero or negative window, or a revocation window not longer than the lockout
window, fails startup. Both defaults belong to `DormancyPolicy`, not
`application.yaml`. The startup record states the job's cron, zone and both
windows; every run logs `job-start` and `job-end` (`event.action:
identity.dormancy`), the end carrying `dormancy.locked_count` and
`dormancy.memberships_revoked_count` (Users whose memberships were revoked), including a run that skipped or changed
nobody.

With the development fixtures on (`APP_DEV_FIXTURES_ENABLED=true`, as
`.env.example` sets) the job also runs once at startup, after seeding has
backdated the `dormant` fixture User past the revocation window and put it back
into the Groups `app.dev-fixtures.dormant-groups` names, so a local run and the
e2e suite see a dormancy lock and every membership removed without waiting for
04:00. That run applies to
every User, as the nightly one would.
