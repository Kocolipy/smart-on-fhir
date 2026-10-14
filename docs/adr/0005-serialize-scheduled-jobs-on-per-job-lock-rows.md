# 5. Serialize scheduled jobs on per-job lock rows

Date: 2026-09-30

## Status

Accepted.

To be superseded in part by ADR 0015 (2026-10-12, SQL Server 2022 replaces
PostgreSQL): the row is taken with `WITH (UPDLOCK, ROWLOCK, READPAST)` in place of
`FOR UPDATE SKIP LOCKED`, or, if spike V3 finds `READPAST` does not skip a held row,
with `sp_getapplock` per job name. The retention job also takes the lock as the app
login rather than before assuming a role. One serialization point per job name, held
for the run's transaction, is unchanged. The superseding note lands with the ticket
that changes the mechanism.

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

Inside a run, each candidate User is re-read under its resource lock (taken, with every
other row the run may change, through the SCIM write lock module `ScimWriteLocks`) and
decided again, so a User is never processed twice even across the window between the
candidate query and the write.

## Consequences

- The lock is released by the commit or rollback that ends the run. There is no lease to
  expire and nothing to release by hand, and a crashed instance's lock goes with its
  connection.
- A run is one transaction. A failure on one User rolls the whole run back, and the next run
  repeats it. That is the fail-closed half of ADR 0004.
- The runtime role (`backend_app`, the app login's since #66) holds `SELECT, UPDATE` on the table, because `FOR UPDATE` requires it, and
  no `INSERT` or `DELETE`, so it cannot remove the row a job serializes on.
- A new job that must not overlap itself needs a `ScheduledJob` member and a migration
  inserting its row.

## Amendment (2026-10-02): the audit retention job

The audit retention job takes the same lock, on its own `audit-retention` row, so
two instances on the same cron no longer both run the delete. It takes the
lock before assuming the retention role, as the application role, which holds the grant.
(Since 2026-10-14, #66, there is no role to assume: the lock is taken on the main pool
as the app login, which alone holds the grant, and the deletes run on the retention
pool as the retention login, inside the lock's transaction — see the 2026-10-13
amendment.)

The port and adapter moved from `auth` to a shared `scheduling` module for it: `audit`
may depend on no business module (`ArchitectureTest.MODULE_DEPENDENCIES`),
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

## Amendment (2026-10-13): one module runs a serialized job

The decision above holds only while three things line up: the transaction the lock
is taken in, the data source the lock row is on, and how long that transaction
lasts. Each job used to line them up itself, with `@Transactional` on its body,
`tryAcquire` on the primary data source and its own skip. Two planned changes would
have broken that silently: moving the retention job to a transaction manager of its
own would have taken the lock in autocommit and released it at once, and one
transaction per batch would have released it after the first batch.

The scheduled-job run module, `ScheduledJobRunService` (`scheduling.application`),
now owns that transaction. A job hands it its `ScheduledJob` and a body. The module
opens the run's own transaction on the primary data source's transaction manager
(`PROPAGATION_REQUIRES_NEW`, through a `TransactionTemplate` rather than an
annotation), takes the lock in it, and reports a skip without running the body when
another run holds it. Otherwise it runs the body inside that transaction and holds
the lock until the body has returned or thrown. A body that throws rolls the run
back. A transaction a body opens of its own — on another data source's
transaction manager, or on the primary one with `PROPAGATION_REQUIRES_NEW` —
commits inside the run's transaction and releases nothing.
`ScheduledJobRunIntegrationTests` proves both cases on Postgres, the first with a
delete committed on the retention pool. A per-batch commit on
the primary data source therefore needs `REQUIRES_NEW`: a per-batch
`@Transactional` with the default `REQUIRED` propagation joins the run's
transaction instead, and nothing commits until the run does. The lock stays on
the primary data source because only the application login holds grants on
`scheduled_job_locks`.

A job body never takes the lock itself. `ScheduledJobRunService` is the only caller
of `ScheduledJobLock`, and `ArchitectureTest.only_the_scheduled_job_run_takes_a_job_lock`
enforces that. The adapter remains the one place the engine's lock syntax is written,
so ADR 0015's switch to `READPAST` changes the adapter and nothing that calls it.
Neither the dormancy job nor the retention job is `@Transactional` any more. Both
bodies still run inside the lock's transaction: dormancy is one transaction per run,
and retention's deletes run on the retention pool, as the retention login, in
transactions of that pool's own transaction manager, which commit inside the run's
lock transaction on the primary data source and release nothing (amended 2026-10-14,
#66: until then retention issued `SET LOCAL ROLE` inside the lock's transaction,
assumed after the lock was taken as the application role).

A job's identity is spelled once. `ScheduledJob` carries the name that is its lock
row, its metrics' `job` tag and its `batch.job.name`, and also the `Operation` its
records are classified as. The schedule configs and the services read both from it.
The values are unchanged (`dormancy` and `audit-retention`, which the V1 migration
and the alert rules name). `scheduling` therefore depends on `observability` for
the `Operation` vocabulary (`ArchitectureTest.MODULE_DEPENDENCIES`).
`observability` still depends on nothing, so `ScheduledJobSpec` keeps taking a name
and an `Operation` rather than a `ScheduledJob`.

A new job that must not overlap itself therefore needs a `ScheduledJob` member, with
its name and `Operation`, and the migration row. Its service hands its body to
`ScheduledJobRunService.run`, and its schedule config names it in a `ScheduledJobSpec`
from that member.

## Alternatives considered

**`pg_try_advisory_xact_lock(hashtext(name))`.** Same transaction scoping with no table. Not
chosen: two job names can collide on a 32-bit hash and would then block each other, which is
the one thing the requirement rules out. The set of serializable jobs would also not be
visible in the schema.

**ShedLock or a similar library.** It adds a dependency and a lease-based model. A lease
expiring during a long run lets two runs overlap.
