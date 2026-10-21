---
type: Configuration Reference
title: Audit trail
description: The audit retention period and schedule, what each retention run logs, the separate pool fail-open audit appends commit on, and the membership operations the trail records.
tags: [audit, retention, configuration]
---

# Audit trail

## Audit trail retention

| Variable                       | Default         | Meaning                                   |
| ------------------------------ | --------------- | ----------------------------------------- |
| `APP_AUDIT_RETENTION_PERIOD`   | `365d` (1 year) | How long a recorded audit event is kept   |
| `APP_AUDIT_RETENTION_SCHEDULE` | `0 30 3 * * *`  | When the retention job runs (Spring cron) |

The **floor is 90 days**, and it is enforced rather than advised: a configured
period below it fails startup with the value in the message, instead of quietly
keeping less history than an investigation needs. `90d` itself is allowed. Both
defaults belong to `AuditRetentionPolicy`, not `application.yaml`.

The job logs its schedule at startup, and each run logs one summary line, its
`job-end` (`app.event.action: audit.retention`): `event.outcome`,
`batch.job.status` (`completed` or `failed`), what it deleted as
`record=[{type: audit_event, deleted: <n>, batches: <m>}]`, and
`event.duration_ms`. A run that deleted nothing reports too.

A run deletes in batches of at most 4,000 events (`DELETE TOP (4000)`), each in a
transaction of its own on the retention pool with a 60 s query timeout, until a
batch comes back short. A batch stays below SQL Server's lock-escalation threshold
of 5,000 locks, so it holds row locks only and an audit append never waits behind
it (ADR 0013). A failed run reports what it had committed: every batch before the
one that failed, or all of them when the run's lock transaction failed afterwards,
since each batch commits on the retention pool before that transaction does; the
next run deletes the rest. The job is serialized on its own `audit-retention` row
in `scheduled_job_locks`, as the [dormancy job](dormancy.md) is, and the lock is held
by the run's main-pool transaction across every batch: with several instances on
the same cron one run deletes, and each other — even one started between two
batches — writes one `skipped` line (`event.reason: lock-held`) and deletes
nothing.

## Audit append pool

| Variable                 | Default | Meaning                                                            |
| ------------------------ | ------- | ------------------------------------------------------------------ |
| `APP_AUDIT_POOL_SIZE`    | `2`     | Connections in the pool fail-open audit appends commit on (2 or 3) |
| `APP_AUDIT_POOL_TIMEOUT` | `2s`    | How long such an append waits for one before alerting (≥ `250ms`)  |

A refusal's audit event — a rejected Login, a refused SCIM write, a refused
token rotation — and a post-commit Session revocation's are appended
**fail-open**, in a transaction of their own (ADR 0004). They commit on a small
pool of their own, never on the main one: a refusing request still holds its
main-pool connection (a rejected Login holds the User row's lock too), so an
append that needed a second connection from the same pool would, under enough
overlapping refusals, wait out the pool's timeout for a connection none of them
would release. Accepted (fail-closed) events still join their request's
transaction on the main pool.

The pool has **no credentials of its own** and no URL setting: it opens its
connections as the main pool does, with the same URL, driver properties and app
login (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`), so appends run
as the app login like every other request's statement, never as the owner login.
It starts lazily, on the first fail-open append. An append that
cannot get a connection within the timeout is lost behind the `ERROR`
`audit.append` alert, as any failed fail-open append is. The pool is named
`audit-append` in Hikari's log lines.

## Membership operations

The trail speaks in Permissions, not Roles (ADR 0015). Three operations record a
change of a User's power through its Group memberships; nothing here configures
them, but they are what an operator searches the trail for:

| Operation                        | Recorded when                                                                                        |
| -------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `PERMISSION_GRANT`               | A connector adds a User to a Group that confers Permissions, or to the Admin group                  |
| `PERMISSION_REVOKE`              | A connector removes it — `PATCH`, `PUT` or the Group's `DELETE`                                       |
| `DORMANCY_MEMBERSHIP_REVOCATION` | The [dormancy job](dormancy.md) removes every direct Group membership of a User past its window      |

Each carries `permissions_before` and `permissions_after` (the User's effective
Permissions, comma-joined) and `superuser_changed`, set when the change granted or
removed the Superuser; the dormancy event also lists every Group removed in
`group_ids`. A Group that confers nothing records neither of the first two.
Migration V5 rewrote the rows recorded before as `ROLE_GRANT`, `ROLE_REVOKE` and
`DORMANCY_ROLE_REVOCATION`, moving each Role's name into the column on its side of
the change, and dropped `role_name` — a one-off deviation from the audit-integrity
rule, recorded in ADR 0015.
