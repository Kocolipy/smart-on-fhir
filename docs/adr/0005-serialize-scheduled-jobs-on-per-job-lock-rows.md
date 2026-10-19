# 5. Serialize scheduled jobs on per-job lock rows

Date: 2026-09-30

## Status

Accepted. Consolidated 2026-10-19 from the original decision and its amendments:
the audit retention job joined it (2026-10-02), the scheduled-job module took
over registration (2026-10-04) and then the run's transaction (2026-10-13),
retention moved to batches inside the lock (2026-10-14), and the lock grant was
narrowed to `SELECT` (2026-10-16).

## Context

Every scheduled job — the dormancy job (ADR 0007) and the audit retention job —
must be serialized per job name so that no two runs of the same job overlap
across instances, and two different jobs must not block each other. Spring's
scheduler only knows about its own process, so every instance fires the same
cron.

The lock holds only while three things line up: the transaction it is taken
in, the data source its row is on, and how long that transaction lasts. Left to
each job, they drift silently: moving a job to a transaction manager of its own
takes the lock in autocommit and releases it at once, and one transaction per
batch releases it after the first batch.

## Decision

**A lock row per job.** Each job has a row in `scheduled_job_locks`, inserted by
the schema migration. A run takes its own row with
`SELECT job_name FROM scheduled_job_locks WITH (UPDLOCK, ROWLOCK, READPAST) WHERE job_name = ?`
and holds the update lock until its transaction ends. A second run of the same
job reads past the held row, gets nothing back without waiting, and skips; a run
of another job locks a different row. Spike V3 verified all three under
read-committed snapshot (ADR 0013, "Verified behaviour"). The empty result is
followed by a plain count, so a missing row fails the run loudly instead of
reading as "someone else is running it" forever. Each statement has a 60 s query
timeout (ADR 0013, "Bounded timeouts"): `READPAST` never waits on a held row, but
would on a lock above it. The port is `ScheduledJobLock` (`scheduling.domain`),
the adapter `ScheduledJobLockAdapter`, the one place the lock syntax is written.

**One module runs a serialized job.** `ScheduledJobRunService`
(`scheduling.application`) owns the run's transaction: a job hands it its
`ScheduledJob` and a body, and it opens the run's own transaction on the primary
data source (`PROPAGATION_REQUIRES_NEW`, through a `TransactionTemplate`), takes
the lock, reports a skip when another run holds it, and otherwise runs the body
inside that transaction, holding the lock until the body returns or throws. A
body that throws rolls the run back. A transaction a body opens of its own — on
another data source's transaction manager, or on the primary one with
`REQUIRES_NEW` — commits inside the run's and releases nothing; a per-batch
`@Transactional` with the default `REQUIRED` joins the run's transaction instead,
and nothing commits until the run does. `ScheduledJobRunIntegrationTests` proves
both, and `ArchitectureTest.only_the_scheduled_job_run_takes_a_job_lock` keeps
every other caller off `ScheduledJobLock`.

So the dormancy job is one transaction per run, and the audit retention job
deletes in batches of 4,000, each committed on the retention pool as the
retention login, inside the run's lock transaction on the main pool — a second
instance started between two batches skips
(`AuditRetentionSerializationIntegrationTests`).

**The app login holds `SELECT` alone on the table.** SQL Server checks a locking
read's permissions as a `SELECT`'s and the application never updates the row,
so the app login (`backend_app`) cannot change or remove the row a job
serializes on; its `UPDATE` is refused with error 229.

**A job's identity is spelled once.** `ScheduledJob` carries the name that is its
lock row, its metrics' `job` tag and its `batch.job.name`, and the `Operation` its
records are classified as (`dormancy`, `audit-retention`, which V1 and the alert
rules name). Registration lives in `ScheduledJobMetrics` (`observability`): a
schedule config names its job in a `ScheduledJobSpec`, and `schedule` builds the
cron task in `ServiceTimeZone.ZONE`, registers the run metrics and declared
counters at zero, and writes the startup record. A run reports its counts once
(`SkippableJobRun.counts()`), after its transaction committed, so a run that
rolled back or skipped counts nothing.

**A new job that must not overlap itself** needs a `ScheduledJob` member, with its
name and `Operation`, and a migration inserting its row; its service hands its
body to `ScheduledJobRunService.run`, and its schedule config names it in a
`ScheduledJobSpec`.

## Consequences

- The lock is released by the commit or rollback that ends the run. There is no
  lease to expire and nothing to release by hand, and a crashed instance's lock
  goes with its connection.
- A dormancy run is one transaction: a failure on one User rolls the whole run
  back, and the next run repeats it — the fail-closed half of ADR 0004. Inside a
  run each candidate User is re-read under its resource lock (`ScimWriteLocks`)
  and decided again, so no User is processed twice.
- `scheduling` depends on `observability` for the `Operation` vocabulary;
  `observability` depends on nothing, so `ScheduledJobSpec` takes a name and an
  `Operation` rather than a `ScheduledJob`.

## Alternatives considered

**An application lock (`sp_getapplock`) per job name.** Same transaction scoping
with no table. Not chosen: the set of serializable jobs would not be visible in
the schema, and a lock on a name no job declares would be granted rather than
fail loudly.

**ShedLock or a similar library.** It adds a dependency and a lease-based model.
A lease expiring during a long run lets two runs overlap.
