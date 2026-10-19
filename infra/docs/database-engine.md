---
type: Deployment Reference
title: Database engine and cost
description: RDS for SQL Server 2022 Standard Edition — edition, instance classes, cost, collation and time zone.
tags: [rds, sql-server, cost]
---

# Database engine and cost

## Engine, edition and cost

- **Engine:** `sqlserver-se`, version `16.00` — SQL Server 2022 — which RDS
  resolves to its current default build. `deploy.sh` asks RDS for that build
  before creating anything (`describe-db-engine-versions --default-only`), and
  `AutoMinorVersionUpgrade` applies newer builds (cumulative updates) in the
  maintenance window, Sunday 04:00–05:00 UTC (ADR 0013, "Database patching").
- **Edition: Standard Edition, licence-included.** The SQL Server licence is part
  of the hourly instance price; there is nothing to bring or to track. It is the
  edition ADR 0013 chose ("Scope") — Express caps a database at 10 GB, and
  Developer Edition may not serve production — and choosing it is a
  stakeholder sign-off.
- **Instance classes:** Standard Edition does not run on the small classes —
  no `db.t3.micro` to `db.t3.large` and no Graviton (`db.t4g.*`) — so
  `DBInstanceClass` allows only classes RDS offers it on: `db.t3.xlarge`
  (default, 4 vCPU / 16 GiB) and `db.t3.2xlarge`, `db.m5`/`db.m6i` `large` to
  `2xlarge`, and `db.r5`/`db.r6i` `large` and `xlarge`. `deploy.sh` checks the
  one you choose is orderable for Standard Edition, licence-included, in the
  region (`describe-orderable-db-instance-options`) before creating anything.

**It is expensive.** The smallest Standard Edition instance carries the instance
price **and** the per-vCPU SQL Server licence, for four vCPUs at least:
`db.t3.xlarge` was US$1.109 an hour (≈ US$810 a month) in ap-southeast-1,
single-AZ, on demand, on 2026-10-15 — about 40 times the `db.t3.micro` the
previous engine ran on — before storage, backups, Performance Insights beyond
its free tier, and Multi-AZ (which doubles the instance price). Take the current
price for the class you choose before sign-off:

```bash
aws pricing get-products --region us-east-1 --service-code AmazonRDS \
  --filters Type=TERM_MATCH,Field=regionCode,Value=ap-southeast-1 \
    Type=TERM_MATCH,Field=databaseEngine,Value="SQL Server" \
    Type=TERM_MATCH,Field=databaseEdition,Value=Standard \
    Type=TERM_MATCH,Field=licenseModel,Value="License included" \
    Type=TERM_MATCH,Field=deploymentOption,Value=Single-AZ \
    Type=TERM_MATCH,Field=instanceType,Value=db.t3.xlarge \
  --query 'PriceList[0]' --output text |
  jq -r '.terms.OnDemand[].priceDimensions[] | "\(.pricePerUnit.USD) USD \(.unit): \(.description)"'
```

An instance that is stopped still bills its storage, and RDS restarts a stopped
instance after seven days; `cleanup.sh` deletes the stack, after a final
snapshot that is itself billed until you delete it.

## Collation and time zone

- **The database is collated `Latin1_General_100_BIN2_UTF8`** — UTF-8 storage in
  code-point order, which the schema and the SCIM sort depend on (ADR 0013, "Runtime model"). It is the **database's** collation, set by
  the bootstrap's `CREATE DATABASE … COLLATE`, which every column takes, and the
  bootstrap refuses a database it finds with any other collation rather than
  altering it (an altered collation would not convert existing columns).
- **The server is collated `Latin1_General_100_BIN2_UTF8` too**
  (`CharacterSetName`), as issue #71 names it, so `master` and `tempdb`
  compare names as the database does. It is fixed at creation; changing it
  replaces the instance.
- **The seed lock does not depend on the server collation.** On the pinned
  Linux image (SQL Server 2022 CU27, `MSSQL_COLLATION`), a server collated
  `Latin1_General_100_BIN2_UTF8` or `Latin1_General_100_BIN2` cannot load the
  internal extended procedures behind `sp_getapplock` (`sys.xp_userlock`) and a
  T-SQL `sp_executesql`: error 17750, "Could not load the DLL (server
  internal)". The seed lock is a row lock on `scim_seed_locks` (`V1`), and
  the bootstrap uses `EXEC (@ddl)` instead of `sys.sp_executesql` (ADR 0013,
  "Addendum (2026-10-16)").
- **Untested risk: Flyway on the Windows build.** Flyway guards every
  migration with `sp_getapplock`. On a BIN2_UTF8 server on the Linux image it
  fails with 17750. RDS runs SQL Server on Windows, and no RDS instance was
  created to check whether the defect exists there.
- **What a failed first migrate looks like.** If the Windows build has the
  same defect, the failure is loud, not silent:
  - `backend-migrate.service` fails, so `backend.service`, which `Requires=`
    it, never starts.
  - The deploy's health check and smoke fail.
  - `sudo journalctl -u backend-migrate` shows `FlywaySqlException: Unable to
acquire SQL Server application lock` with `Error Code : 17750`.
  - On a later deploy, `deploy.sh` prints that journal and stops with the
    service still stopped.
  - The way out is a server collation that does not fail. On the Linux image,
    `Latin1_General_100_CI_AS_SC_UTF8` and `Latin1_General_100_CS_AS_SC_UTF8`
    take the lock, and RDS's default does too. Changing `CharacterSetName`
    replaces the instance, which costs nothing on a stack that never migrated.
- **Local and test servers keep their default server collation.** Their
  databases are still `Latin1_General_100_BIN2_UTF8`. The compose server and
  the test containers run that Linux image, where a BIN2_UTF8 server would fail
  every Flyway migration. Replacing Flyway's lock was considered and declined.
  So the deployed server's collation is the one setting the suite does not
  reproduce.
- **The server's time zone is UTC**, stated (`Timezone: UTC`) rather than
  defaulted: storage is UTC only (ADR 0013, "Storage is UTC only").

Both are fixed when the instance is created; changing either replaces it.
