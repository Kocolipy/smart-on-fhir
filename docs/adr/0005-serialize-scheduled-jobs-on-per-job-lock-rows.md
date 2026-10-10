# 5. Serialize scheduled jobs on per-job lock rows

Date: 2026-09-30

## Status

Accepted.

## Context

Every scheduled job — today the dormancy job (ADR 0011) and the audit retention job — must
be serialized per job name so that no two runs of the same job overlap across instances,
and two different jobs must not block each other. Spring's
scheduler only knows about its own process, so any number of instances would each fire the
same cron. Nothing in the codebase serialized work across instances before this.

## Decision

Each job has a row in `scheduled_job_locks`, inserted by the schema migration. A run opens its transaction, takes its own row with
`SELECT … FOR UPDATE SKIP LOCKED`, and holds it until the transaction ends. An empty result
means another run holds it, and the run skips rather than waits. A missing row fails the run
loudly instead of reading as "someone else is running it" forever. The port is
`ScheduledJobLock` (`scheduling.domain`), the adapter `ScheduledJobLockAdapter`
(`scheduling.infrastructure.persistence`).

Inside a run, each candidate User is re-read under its resource lock
(`findByIdForUpdate`) and decided again, so a User is never processed twice even across the
window between the candidate query and the write.

## Consequences

- The lock is released by the commit or rollback that ends the run. There is no lease to
  expire and nothing to release by hand, and a crashed instance's lock goes with its
  connection.
- A run is one transaction. A failure on one User rolls the whole run back, and the next run
  repeats it. That is the fail-closed half of ADR 0004.
- The runtime role holds `SELECT, UPDATE` on the table, because `FOR UPDATE` requires it, and
  no `INSERT` or `DELETE`, so it cannot remove the row a job serializes on.
- A new job that must not overlap itself needs a `ScheduledJob` member and a migration
  inserting its row.

## Amendment (2026-10-02): the audit retention job

The audit retention job takes the same lock, on its own `audit-retention` row, so
two instances on the same cron no longer both run the delete. It takes the
lock before assuming the retention role, as the application role, which holds the grant.

The port and adapter moved from `auth` to a shared `scheduling` module for it: `audit`
may depend on no business module (`ArchitectureTest.audit_depends_only_on_observability`),
so it could not reach a port in `auth.domain`. The mechanism is unchanged.

## Amendment (2026-10-04): the scheduled-job module registers every job

Registration moved into the scheduled-job module, `ScheduledJobMetrics` (`observability`), so
that it is in one place. A schedule config now only names its job in a
`ScheduledJobSpec`: its name, `Operation`, cron, description, task, the fields particular to
its startup record, and which of its run counts are also counters. `ScheduledJobMetrics.schedule`
builds the cron task and its trigger in `ServiceTimeZone.ZONE`, registers the run metrics and
the declared counters at zero, and writes the startup record. That record is now logged under
the `ScheduledJobMetrics` logger rather than the config's.

A run reports its counts once (`SkippableJobRun.counts()`). Each count becomes a field on the
`job-end` record and moves the counter declared for it. Counting happens only after the run
has returned, and so after its transaction committed, and only when it did the work. A run
that rolled back or skipped on a held lock therefore counts nothing, which is the same
guarantee the dormancy counters had before. The lock mechanism above is unchanged.

A new scheduled job therefore needs a `ScheduledJob` lock member and its migration row, as
before, plus one `ScheduledJobSpec`.

## Alternatives considered

**`pg_try_advisory_xact_lock(hashtext(name))`.** Same transaction scoping with no table. Not
chosen: two job names can collide on a 32-bit hash and would then block each other, which is
the one thing the requirement rules out. The set of serializable jobs would also not be
visible in the schema.

**ShedLock or a similar library.** It adds a dependency and a lease-based model. A lease
expiring during a long run lets two runs overlap.
