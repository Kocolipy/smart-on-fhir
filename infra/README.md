# AWS CloudFormation Deployment Guide

Deploy the Spring Boot backend to AWS ap-southeast-1 with an ALB, EC2, **RDS for
SQL Server 2022 Standard Edition**, and Redis.

This directory is the repo-root `infra/` folder — a sibling of `backend/` and
`frontend/`, not part of either app. All commands below are run from `infra/`.

## Quick Start (5 Minutes)

```bash
cd infra

# 1. Get your VPC details
./get-vpc-info.sh vpc-YOUR_VPC_ID

# 2. Deploy everything
./deploy.sh
```

The script prompts for VPC IDs and sizing — **never for a password**: every
credential is generated into Secrets Manager by the stack (see
[Database security](#database-security-secrets-key-tls-monitoring)). It checks
the engine version and instance class with RDS, stages the template in S3,
deploys the stack, retrieves the SSH key, waits for the instance to boot — which
**bootstraps the database itself** — re-runs that bootstrap to prove it a no-op
(`db-bootstrap.sh --expect-no-op`), and runs the
[post-deploy smoke](#post-deploy-smoke) (`smoke.sh`), which fails the deploy if
any secret value leaked: the full smoke when you answer yes to the JAR prompt and
it deploys the application, and its leak check over the boot alone when you
answer no.

Needs, besides the AWS CLI: `jq`, `curl`, `ssh`/`scp`, `openssl` and `base64`.
No database client: everything that talks to the database runs on the instance.

> **Before taking traffic:** attach an AWS WAF web ACL to the ALB. The stack
> does not create one, and the application has no rate limiter of its own. See
> [Edge throttling](#edge-throttling-required).

> **Cost:** SQL Server Standard Edition, licence-included, costs far more than
> the database instance it replaces. Read
> [Engine, edition and cost](#engine-edition-and-cost) before deploying.

---

## Architecture

```
Internet → ALB (HTTP:80) → EC2 (8080) → RDS for SQL Server (1433) + Redis
           (public)         (public)     (private subnets)
```

**Components:**

- Application Load Balancer (internet-facing)
- EC2 instance (Amazon Linux 2023 + Java 25, and `sqlcmd` for the database
  bootstrap)
- RDS for SQL Server 2022 Standard Edition, licence-included (encrypted under a
  customer-managed KMS key, TLS 1.2+ forced, Enhanced Monitoring and Performance
  Insights, automated backups)
- Secrets Manager secrets for every database credential, the driver's trust
  store and the application's own secrets
- ElastiCache Redis (session storage)
- An SNS topic as the stack's alarm target, and two database alarms
- Security groups with proper isolation

---

## Edge throttling (required)

The application has **no request-rate limiter**. Throttling is the deployment
edge's job, and a deployment that exposes the service without it is
misconfigured. In this stack the edge is the ALB: port 8080 admits only the ALB's
security group, so an AWS WAF web ACL associated with the ALB sees every request.

**`infrastructure.yaml` provisions no web ACL.** Attach one before the service
takes traffic.

### What must be throttled

| Surface                      | Path                             | Rate-based key                                   |
| ---------------------------- | -------------------------------- | ------------------------------------------------ |
| SCIM                         | `/scim/v2/` and everything below | the `Authorization` header (one connector token) |
| Login                        | `POST /api/auth/login`           | count-all, scoped down to the path               |
| Self-service password change | `POST /api/auth/change-password` | the `JSESSIONID` cookie (one session)            |
| Epic Login launch            | `GET /api/auth/epic/launch`      | count-all, scoped down to the path               |
| Epic Login callback          | `GET /api/auth/epic/callback`    | count-all, scoped down to the path               |

Also give `/scim/v2/` a count-all ceiling scoped down to the path, so random
bearer values cannot dodge the per-token rule by presenting a different key on
every request. Set the thresholds from the service's own telemetry (see
[Operational telemetry](#operational-telemetry)) after a week of normal traffic.

### Why per account, and not per source

**Per-account throttling is the primary deterrent.** A brute-force or exhaustion
attempt is aimed at one credential, so the limit belongs on the credential:

- **SCIM:** each connector token is its own key. One misbehaving or stolen token
  is bounded without throttling every other connector.
- **Self-service change:** each session is its own key. A wrong current password
  also lengthens the User's failure run, so guessing there ends in the same
  lockout as Login.
- **Login:** AWS WAF rate-based rules key on headers, cookies, query arguments,
  path, method, IP and labels, but **not on the request body**, so the edge
  cannot count attempts per `userName`. The application does that half. A User's
  failure run locks it after 3 consecutive rejections, and only an Admin's
  Unlock lifts the lock. The Bootstrap Admin is the exception: its failures are
  counted and audited, but it never locks. The edge's Login rule therefore caps
  total cost. Every attempt pays an Argon2id verification, so unthrottled Login
  is a CPU-exhaustion lever. An edge that can key on a JSON body field may add a
  per-`userName` limit on top.
- **Epic Login** (ADR 0013, D19): the launch and the callback are browser
  navigations from Epic, made before there is any session or credential of ours
  to key on, so the edge's rule caps their total cost. The callback matters most:
  every callback makes an outbound token call to Epic, so an unthrottled callback
  would let anyone use this server to flood Epic. A breach is answered `429` with
  `Retry-After`, as for every other surface. The limits are edge configuration,
  not part of the application.

**Per-source (per-IP) throttling is not required and not assumed.** It is left
to the edge's own policy. Behind a corporate proxy or NAT gateway, many
legitimate Users share one address. A per-IP limit tight enough to deter
guessing would lock them out together, and one loose enough not to would deter
nothing. A coarse per-IP flood guard is fine as an addition, but nothing here
relies on it.

### Keep the keys out of WAF logs

The two key fields are credentials. If WAF logging is enabled, add `authorization`
and `cookie` to the logging configuration's `RedactedFields` (as `SingleHeader`).
Otherwise every logged request writes a bearer token or a session cookie to the
log destination.

---

## Database security (secrets, key, TLS, monitoring)

What the stack does for IM8 as-8, ck-2/ck-4, dp-3 and lm-16 on RDS for SQL
Server (ADR 0015, "Compliance resolutions" and "Standards resolutions"). The
previous stack's controls were re-targeted, not replaced: the same key, the same
secrets discipline, the same migrate step.

### Engine, edition and cost

- **Engine:** `sqlserver-se`, version `16.00` — SQL Server 2022 — which RDS
  resolves to its current default build. `deploy.sh` asks RDS for that build
  before creating anything (`describe-db-engine-versions --default-only`), and
  `AutoMinorVersionUpgrade` applies newer builds (cumulative updates) in the
  maintenance window, Sunday 04:00–05:00 UTC (ADR 0015, "Database patching").
- **Edition: Standard Edition, licence-included.** The SQL Server licence is part
  of the hourly instance price; there is nothing to bring or to track. It is the
  edition ADR 0015 chose ("Scope") — Express caps a database at 10 GB, and
  Developer Edition may not serve production — and choosing it is a
  stakeholder sign-off.
- **Instance classes:** Standard Edition does not run on the small classes —
  no `db.t3.micro` to `db.t3.large` and no Graviton (`db.t4g.*`) — so
  `DBInstanceClass` allows only classes RDS offers it on: `db.t3.xlarge`
  (default, 4 vCPU / 16 GiB) and `db.t3.2xlarge`, `db.m5`/`db.m6i` `large` to
  `2xlarge`, and `db.r5`/`db.r6i` `large` and `xlarge`. `deploy.sh` checks the
  one you choose is orderable for Standard Edition, licence-included, in the
  region (`describe-orderable-db-instance-options`) before creating anything.

**This is a cost change.** The smallest Standard Edition instance carries the
instance price **and** the per-vCPU SQL Server licence, for four vCPUs at least.
AWS's price list for ap-southeast-1, single-AZ, on demand, read on 2026-10-15:

| Instance                                     | Per hour | Per month (730 h) |
| -------------------------------------------- | -------- | ----------------- |
| `db.t3.micro`, the previous stack            | US$0.028 | ≈ US$20           |
| `db.t3.xlarge`, SQL Server SE, licence-incl. | US$1.109 | ≈ US$810          |

— **about 40 times the previous instance**, before storage, backups,
Performance Insights beyond its free tier, and Multi-AZ (which doubles the
instance price). Prices change; take the current one for the class you choose
before sign-off:

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

### Collation and time zone

- **The database is collated `Latin1_General_100_BIN2_UTF8`** — UTF-8 storage in
  code-point order, which the schema and the SCIM sort depend on (ADR 0015,
  "VARCHAR with database collation"). It is the **database's** collation, set by
  the bootstrap's `CREATE DATABASE … COLLATE`, which every column takes, and the
  bootstrap refuses a database it finds with any other collation rather than
  altering it (an altered collation would not convert existing columns).
- **The server is collated `Latin1_General_100_BIN2_UTF8` too**
  (`CharacterSetName`), as issue #71 names it, so `master` and `tempdb`
  compare names as the database does. It is fixed at creation; changing it
  replaces the instance.
- **The seed lock no longer depends on the server collation.** On the pinned
  Linux image (SQL Server 2022 CU27, `MSSQL_COLLATION`), a server collated
  `Latin1_General_100_BIN2_UTF8` or `Latin1_General_100_BIN2` cannot load the
  internal extended procedures behind `sp_getapplock` (`sys.xp_userlock`) and a
  T-SQL `sp_executesql`: error 17750, "Could not load the DLL (server
  internal)". The seed lock is now a row lock on `scim_seed_locks` (`V1`), and
  the bootstrap uses `EXEC (@ddl)` instead of `sys.sp_executesql` (ADR 0015,
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
  defaulted: storage is UTC only (ADR 0015, "Storage is UTC only").

Both are fixed when the instance is created; changing either replaces it.

### Database credentials

**Every database credential is in AWS Secrets Manager.** None is a
CloudFormation parameter, none is in UserData or in a file on the instance, and
`deploy.sh` prompts for none.

| Secret (stack output)   | Holds                                        | Created by                                           | Read by                                                    |
| ----------------------- | -------------------------------------------- | ---------------------------------------------------- | ---------------------------------------------------------- |
| `DBMasterSecretArn`     | the RDS master user (`DBMasterUsername`)     | RDS (`ManageMasterUserPassword`)                     | the instance's database bootstrap only; `smoke.sh`         |
| `DBOwnerSecretArn`      | the owner login (`DBOwnerLogin`)             | CloudFormation, generated                            | the bootstrap, the migrate step; `smoke.sh`                |
| `DBAppSecretArn`        | the app login (`DBAppLogin`)                 | CloudFormation, generated                            | the bootstrap, the service; `smoke.sh`                     |
| `DBRetentionSecretArn`  | the retention login (`DBRetentionLogin`)     | CloudFormation, generated                            | the bootstrap, the service; `smoke.sh`                     |
| `DBTrustStoreSecretArn` | the driver-scoped trust store's password     | CloudFormation, generated                            | the boot (builds the store), the service, the migrate step |
| `AppBootstrapSecretArn` | the Bootstrap Admin (`AppBootstrapUsername`) | CloudFormation, generated                            | the service; `smoke.sh` replaces the first password        |
| `EpicClientSecretArn`   | the Epic client id and signing keys (JSON)   | CloudFormation, from the `AppEpicClient*` parameters | the service                                                |

Each generated password is 32 characters and excludes `'`, `"`, `\` and `;`.
Each secret is encrypted under the stack's [database key](#database-key). There
is no rotation yet (#73).

**The instance role reads these seven secrets and nothing else** —
`GetSecretValue` on the seven ARNs, and `kms:Decrypt` on the database key through
Secrets Manager only. Within the instance the separation is **per process**:
each secret is read by one root-owned script into the environment of the one
process that needs it.

- `/opt/backend/db-bootstrap.sh` (root) reads the master secret and the three
  login secrets;
- `/opt/backend/migrate.sh` reads the owner secret and the trust-store password;
- `/opt/backend/start.sh` reads the app, retention, trust-store, Bootstrap Admin
  and Epic secrets — never the owner's or the master's.

**The master secret is now readable by the instance role.** The previous stack
kept it on the operator's machine and ran the bootstrap there, through an SSH
tunnel; this stack runs the bootstrap in UserData, as #71 specifies, so the
instance must read it. The role cannot tell processes apart, so code running as
any user on the instance — the service's JVM included, if it were compromised —
could fetch the master secret through the instance's credentials. Two things
bound that: the service's own process is never given it (`smoke.sh` checks its
`/proc/<pid>/environ`), and the master user's reach beyond what the owner secret
— which the role already had to read for the migrate step — gives is small: the
owner is `db_owner` of the only application database, and the master user adds
the server-level rights to create logins and databases. Blocking the service
user from the instance metadata service would close it; that is follow-up work.

**The service resolves its credentials at every start.** UserData writes
`/opt/backend/start.sh` (root-owned) and the systemd unit runs it. It reads the
secrets through the instance role, puts each value into the JVM's environment
and `exec`s Java: no value is ever written to disk, passed as a command-line
argument (each reaches `jq` on a pipe) or traced. `/opt/backend/.env` holds the
secrets' ARNs, never their values. A secret that cannot be read, or lacks its
password, stops the start before the JVM runs, and systemd retries.

**The service connects as the app and retention logins, never the owner.**
**The application's own secrets** are held the same way, so UserData carries no
secret at all: the Bootstrap Admin's first password (generated), and the Epic
client id and signing keys. The Epic values still enter through `NoEcho`
CloudFormation parameters, which the stack writes into `EpicClientSecretArn`;
they may contain neither `"` nor `\`. Redis has no password: the stack's
single-node `CacheCluster` cannot carry an AUTH token (only a replication group
can), so the old `RedisPassword` parameter, which CloudFormation rejected, is
gone; Redis is reachable only from the instance's security group.

### Boot sequence

UserData runs once, when the instance is created, with `set -euo pipefail` and
never `set -x`:

1. **The SQL Server command-line tools.** `sqlcmd` on the Microsoft ODBC Driver
   18 (`mssql-tools18`, `msodbcsql18`) from Microsoft's signed RHEL 9 package
   repository; `ACCEPT_EULA=Y` accepts their licence terms, which installing
   them requires. Then `cfn-init` writes the database bootstrap's three files and
   the secret-reading helper (`/opt/backend/secret.sh`) from the instance's
   `AWS::CloudFormation::Init` metadata, and UserData builds the RDS CA bundle
   and the driver-scoped trust store
   ([TLS to the database](#tls-to-the-database)).
2. **The database bootstrap**, as the RDS master user
   ([Database bootstrap](#database-bootstrap)): UserData starts
   `backend-db-bootstrap.service`. A failed bootstrap fails the boot, and
   `deploy.sh` stops there.
3. **The migrate step** (`backend-migrate.service`, [Migrate step](#migrate-step)).
4. **The service** (`backend.service`), with the credentials `start.sh` resolves
   and `APP_DEV_FIXTURES_ENABLED=false`: no development fixture account is ever
   seeded (ADR 0015, "Admin and fixture seeding are unchanged"); the Bootstrap
   Admin and the Admin group are seeded by the service itself.

Steps 3 and 4 need the JAR, which does not exist at boot: `deploy.sh` copies it
once the stack is up and starts them then. The units chain the order: the
service `Requires=` the migrate step, which `Requires=` the bootstrap, so every
start of the service after a deploy runs the bootstrap again (a no-op) and the
migrations before the JVM.

The bootstrap's files are in `cfn-init` metadata rather than UserData because EC2
caps UserData at 16 KB; for the same reason of size, the template is larger than
CloudFormation's 51,200-byte inline limit, so `deploy.sh` stages it in S3 and
creates the stack from its URL (`CFN_TEMPLATE_BUCKET`, or a bucket
`cfn-templates-<account>-<region>` it creates once, every public access blocked).
The template holds no secret.

### Database bootstrap

RDS for SQL Server creates no database of its own (the instance has no
`DBName`), so the instance's bootstrap creates the application database and the
three logins, as the RDS master user. It runs as root from
`backend-db-bootstrap.service`: at boot (UserData), before every migrate step,
and on demand with `./db-bootstrap.sh <stack>`.

`/opt/backend/db-bootstrap.sh`:

1. reads the master secret and the owner, app and retention secrets into shell
   variables (with `/opt/backend/secret.sh`, the instance's one secret-reading
   helper, which UserData and `migrate.sh` source too), and checks each login name and `DBName` against
   `^[a-z][a-z0-9_]{2,30}$` (which CloudFormation also enforces), that the three
   logins differ, and that no password holds a quote (ADR 0015, as-2);
2. waits, for up to five minutes, until the master user signs in;
3. signs in as each login with its secret's password, and names each one that
   cannot to the SQL (`RESET_LOGINS`, `|`-separated);
4. runs `/opt/backend/db-bootstrap.sql` as the master user, which
   - creates each **missing** login with its secret's password (`CREATE LOGIN`,
     the name wrapped in `QUOTENAME`, the password escaped), and resets the
     password (`ALTER LOGIN`) of an existing one named in `RESET_LOGINS` — the
     one way a secret changed by hand is re-applied;
   - creates the database if it is missing, `COLLATE Latin1_General_100_BIN2_UTF8`,
     and refuses one found with another collation;
   - sets compatibility level **160**, `READ_COMMITTED_SNAPSHOT ON` and
     `ALLOW_SNAPSHOT_ISOLATION ON`, each only if it is not already set — the
     database creation options ADR 0015 assigns to whatever creates the database,
     since `ALTER DATABASE … SET READ_COMMITTED_SNAPSHOT` cannot run in a
     migration's transaction;
   - makes the owner login a database user of its own name in **`db_owner`**;
5. signs in as each login again, and fails when one still cannot.

**It creates server logins and the database only.** The roles
`backend_app` and `backend_audit_retention`, the app and retention logins'
database users and every grant are `V1`'s, which the migrate step applies as the
owner login with the login names as Flyway placeholders. The Bootstrap Admin and
the Admin group are the service's (never the bootstrap's).

**The owner login is a `db_owner` member, not the database's owner.** The master
user creates the database, so it is its owner (`dbo`); handing it to another
login (`ALTER AUTHORIZATION`) needs `IMPERSONATE` on that login, which RDS's
master user does not hold. `db_owner` membership is everything the migrations
need, and the local init and the tests build their databases the same way, so
the whole suite runs `V1` under it (`MigrationStepIntegrationTests`).

**It is idempotent: a re-run is a no-op.** Every statement is guarded: a login,
the database, an option or the membership that is already in place is left
alone, and each change it does make prints a `db-init: change:` line. A login's
password is reset only when the login cannot sign in with its secret's —
re-setting an unchanged password would itself be a change (and could trip SQL
Server's password-history policy). `./db-bootstrap.sh <stack>` re-runs it and
counts those lines; `deploy.sh` runs it with `--expect-no-op` right after the
boot, so a fresh stack proves the re-run changes nothing.

**It echoes no secret** (ADR 0015, "The bootstrap does not echo"). No `set -x`;
`sqlcmd` runs without `-e`; the signing-in login's password reaches `sqlcmd` as
`SQLCMDPASSWORD`, and every other value as a `sqlcmd` scripting variable read
from its environment — `$(DATABASE_OWNER_PASSWORD)` in the SQL, never `-v` on a
command line, where `ps` would show it. `sqlcmd` does not expand a `$(` inside a
variable's value, so a generated password holding one is safe. Its output, in
the journal and the boot log, holds names and outcomes only, and the
[post-deploy smoke](#post-deploy-smoke) scans both for every secret value.

**One T-SQL, two copies.** The SQL is, from `SET NOCOUNT ON` down, line for line
`backend/scripts/local-db-init.sql`, which builds the local database the same way
(the local server's `sa` in place of the master user). A stack cannot read a file
from this repository, so the text is held twice: `./check-bootstrap-parity.sh`
fails, with the difference, when the copies drift. `deploy.sh` runs it before
every deploy; run it yourself after changing either. The shell around the SQL
is small and differs by design (`sa` and `-C` locally, the master user and `-N`
here); every decision, the password reset included, is in the shared SQL.

### Migrate step

**The running service never holds the owner credential** (ADR 0015, ac-1). The
schema migrations run beforehand, in a **migrate step**: the same JAR, started at
its migrate entry point (`MigrationStep`) with no web server, which applies the
Flyway migrations as the owner login and exits. The service itself runs with
Flyway off and is given only the app and retention logins; `ddl-auto: validate`
still fails its start on a schema that drifted.

On the instance:

- `backend-migrate.service` (oneshot, `RemainAfterExit=yes`, user `springboot`)
  runs `/opt/backend/migrate.sh` (root-owned). It loads `/opt/backend/.env` and
  `/opt/backend/migrate.env` — the owner secret's ARN and the app and retention
  logins' **names** — reads the owner secret and the trust store's password into
  its own environment exactly as `start.sh` reads the others (on a pipe, never an
  argument, never on disk), and `exec`s
  `java -Dloader.main=com.example.backend.database.config.MigrationStep
-cp backend.jar org.springframework.boot.loader.launch.PropertiesLauncher`.
  Before any DDL it refuses a login name outside `^[a-z][a-z0-9_]{2,30}$`.
- It `Requires=` and is ordered `After=` `backend-db-bootstrap.service`, so it
  never migrates a database the bootstrap could not finish.
- `backend.service` `Requires=` and is ordered `After=` it, so starting the
  service runs the step first, and a failed migration fails that start before the
  service's JVM runs. A reboot runs the step again (a no-op when nothing is new).
- `start.sh` reads `DATABASE_APP_SECRET_ARN` (→ `DATABASE_USERNAME` /
  `DATABASE_PASSWORD`, the main and audit append pools) and
  `DATABASE_RETENTION_SECRET_ARN` (→ `DATABASE_RETENTION_USERNAME` /
  `DATABASE_RETENTION_PASSWORD`, the retention pool) and never the owner secret.
  `/opt/backend/.env` does not even name the owner secret; only `migrate.env`,
  which the service never loads, does.

**A deploy runs the migrate step and then the service.** `deploy.sh` copies the
JAR, stops the service, `systemctl restart backend-migrate`s (which re-runs the
bootstrap and then the migrations against the new JAR) and starts the service
only if that succeeded. A failed migration prints the journal and ends the
deploy with the service still stopped, so a schema the new JAR does not match is
never served. Fix the migration (or the database) and deploy again.

`smoke.sh` checks the separation on every deploy: the running service's own
environment (`/proc/<pid>/environ`) must name no `DATABASE_OWNER_*` variable and
contain neither the owner's nor the master secret's ARN or password, and the
migrate step's journal and `migrate.env` join the secret-leak scan.

### Database key

One customer-managed KMS key (`DBKeyArn`, alias `alias/<stack>-database`), with
**automatic rotation enabled**, encrypts RDS storage — and with it the automated
backups and every snapshot — Performance Insights, the RDS-managed master secret
and the six secrets above. `StorageEncrypted` stays on.

Its key policy allows exactly two things:

- **administration** by `KeyAdminArn` — the IAM role or user that deploys the
  stack, which `deploy.sh` fills from your caller identity (an assumed role's
  session becomes the role's ARN, path included). It may describe, enable,
  disable, tag, rotate, alias, revoke grants on and schedule the deletion of the
  key, and may **not** encrypt, decrypt or create grants;
- **use** by principals of this account only through RDS or Secrets Manager
  (`kms:ViaService`), and only when their own IAM policy also allows it — the
  instance role's allows `kms:Decrypt` through Secrets Manager alone.

The key is **retained when the stack is deleted**: the final snapshot RDS takes
then is encrypted under it, and would be unreadable without it. Schedule its
deletion yourself once that snapshot is no longer needed.

### TLS to the database

- **Server-side (IM8 dp-3):** the stack's own parameter group (family
  `sqlserver-se-16.0`, output `DBParameterGroupName`) sets `rds.force_ssl = 1`,
  and `rds.tls10` and `rds.tls11` to `disabled`, so the server encrypts **every**
  session and none runs below TLS 1.2. **A non-TLS connection is refused:** a
  client that cannot do TLS (its TDS PRELOGIN says `ENCRYPT_NOT_SUP`) is answered
  `ENCRYPT_REQ`, and its plaintext login is dropped unread. A client that merely
  *asks* for no encryption but can do TLS (`sqlcmd -No`, a JDBC URL with
  `encrypt=false`) is not refused: the server encrypts its session instead. No
  RDS for SQL Server setting refuses that client — the `sqlserver-se-16.0`
  family's TLS parameters are `rds.force_ssl`, `rds.tls10`/`11`/`12`,
  `rds.fips` and the cipher switches, and SQL Server 2022's "force strict
  encryption" (TDS 8.0 only) is not among them — so either way no session to
  this server is ever plaintext.
- **The service:** `DATABASE_URL` is
  `jdbc:sqlserver://<endpoint>:1433;databaseName=<DBName>;encrypt=true;trustStore=/opt/backend/rds-truststore.p12;trustStoreType=PKCS12`,
  and `database.yaml` adds `sslProtocol=TLSv1.2` for every connection. There is
  no `trustServerCertificate`, so the driver validates RDS's certificate chain
  against the trust store and its name against the endpoint.
- **The driver-scoped trust store** (ADR 0015, "The RDS CA trust store is
  driver-scoped"): at boot UserData downloads the region's RDS CA bundle
  (`https://truststore.pki.rds.amazonaws.com/<region>/<region>-bundle.pem`) and
  imports every certificate in it into `/opt/backend/rds-truststore.p12`, a
  PKCS12 store under the password `DBTrustStoreSecretArn` holds (`keytool` reads
  it from its environment, `-storepass:env`). Only the SQL Server driver opens
  it, named by the JDBC URL; `start.sh` and `migrate.sh` hand it its password as
  `SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_TRUSTSTOREPASSWORD`, which Boot
  binds into the driver properties every pool and the migrate step's connection
  are opened with, never into the URL. **The JVM's default trust store is
  untouched** — there is no `-Djavax.net.ssl.trustStore` — so the identity
  provider's discovery document and JWKS, and every other Epic call, keep
  validating against the JDK's own CAs. The service makes those calls on a
  launch's first use, never at startup (ADR 0013), so `smoke.sh` proves it
  directly: the running JVM has no `javax.net.ssl.trustStore` (`jcmd
VM.system_properties`), and the same JDK, as the service user and with its
  default trust store, fetches Epic's discovery document and the JWKS it names
  when Epic Login is on (a public AWS HTTPS endpoint when it is off).
- **The bootstrap's `sqlcmd`:** `-N` (encryption mandatory), **never `-C`**, so
  the ODBC driver validates RDS's certificate chain and its name against the
  endpoint. The RDS CA bundle is given to `sqlcmd` alone, as `SSL_CERT_FILE` in
  its own environment, which the driver's OpenSSL reads; the operating system's
  trust store is not changed, so no other program on the instance gains trust in
  it. Tested against a SQL Server whose certificate only a test CA vouched for:
  without the bundle, with an unrelated CA, or by an address the certificate does
  not name, `sqlcmd -N` refuses with `certificate verify failed`.

`smoke.sh` proves it on every deploy: the parameter group holds the three
values and is in sync on the instance; then, on the instance:

- **a client that cannot do TLS is refused** — `tds-plaintext-probe.py`
  (standard-library Python, piped to the instance's `python3`) speaks TDS
  itself: a PRELOGIN saying `ENCRYPT_NOT_SUP`, then a plaintext LOGIN7 for a
  login that does not exist with a random password, so no credential travels in
  the clear. It passes only when the server drops that login unanswered; a
  server that answers it at all, even with a failed-login error, accepted a
  plaintext session. Against SQL Server 2022 CU27 with forced encryption the
  probe reports `ENCRYPT_REQ` and the dropped login; without it, the server
  answers in plaintext and the probe fails;
- a session that asks for no encryption (`sqlcmd -No`) is encrypted anyway
  (`encrypt_option` `TRUE`), one that cannot validate the certificate is
  refused, and one that validates it connects, encrypted.

### Database monitoring

- **Enhanced Monitoring** every 60 seconds (`RDSMonitoringRole`).
- **Performance Insights**, 7 days' retention, encrypted under the database key.
- **Alarms**, both on SQL Server's own performance counters, which RDS for SQL
  Server publishes through Performance Insights only — the `AWS/RDS` CloudWatch
  namespace has no deadlock or blocking metric for it — read through
  CloudWatch's `DB_PERF_INSIGHTS` metric math. Each fires only when **every
  minute of five** is above zero, and both notify the stack's notification
  target, the SNS topic `AlarmTopicArn`, on alarm and on recovery:

  | Alarm (stack output)         | Performance Insights counter              | Fires on                                   |
  | ---------------------------- | ----------------------------------------- | ------------------------------------------ |
  | `DBDeadlocksAlarmName`       | `db.Locks.Number of Deadlocks/sec`        | deadlocks in every minute for 5 minutes    |
  | `DBBlockedSessionsAlarmName` | `db.General Statistics.Processes blocked` | blocked sessions in every minute for 5 min |

  Both need Performance Insights on (the stack turns it on), and neither needs
  Enhanced Monitoring or a custom metric. `smoke.sh` checks that the instance's
  Performance Insights offers both counters
  (`aws pi list-available-resource-metrics`), so a renamed counter fails the
  deploy rather than leaving an alarm on a metric that never arrives. Missing
  data is not treated as OK: a counter that stops arriving shows as
  `INSUFFICIENT_DATA`, not as a quiet `OK`. Give `AlarmEmail` to subscribe an
  address (AWS mails a confirmation link first), or subscribe any other endpoint
  to `AlarmTopicArn`.

### Post-deploy smoke

`./smoke.sh <stack> [region]` checks a running stack and exits non-zero on the
first failure. `deploy.sh` runs it after deploying the JAR; run it yourself after
any later deploy.

`./smoke.sh --leaks-only <stack> [region]` runs step 6 alone, over the sources
that exist before the service does — the rendered UserData, the `cfn-init`
metadata and logs, the cloud-init logs, the env files and the database
bootstrap's journal — and needs no JAR. `deploy.sh` runs it when you decline the
JAR prompt, so every deploy scans the boot.

1. **Health:** `/actuator/health` through the ALB answers `200`. **JVM trust:**
   the service's JVM keeps the JDK's default trust store, and that JDK fetches
   the identity provider's discovery document and JWKS over it
   ([TLS to the database](#tls-to-the-database)).
2. **TLS:** see [TLS to the database](#tls-to-the-database).
3. **SCIM:** as the Bootstrap Admin, it mints a one-day token on a throwaway
   connector, then over SCIM creates a User, finds it in the list, finds **no
   development fixture account** — none of the userNames
   `backend/src/main/resources/authorization.yaml` gives its fixtures
   (`app.authorization.dev-fixtures`), read from that file so that a fixture
   added there is checked too — deletes the User and confirms it is gone,
   then deletes the connector (revoking the token) and signs out. On a fresh
   stack the first sign-in must replace the seeded password: the smoke generates
   a new one, stores it in `AppBootstrapSecretArn` as `AWSPENDING`, changes it,
   then promotes it to `AWSCURRENT`. **After the first smoke the secret holds the
   Bootstrap Admin's current password** — read it from there. If the promotion
   fails after the change, the message names the pending version to promote by
   hand.
4. **Alarms:** both exist, notify `AlarmTopicArn` (it warns when nobody is
   subscribed yet), and read counters the instance offers.
5. **Owner and master logins:** the running service's environment holds no part
   of the owner or the master secret.
6. **Secret leaks:** every database password (the master's included), the
   trust store's password, the Bootstrap Admin's current and previous passwords
   and the Epic credentials are searched for in the **rendered UserData** (read
   back from EC2), the instance's **`cfn-init` metadata**, the instance's
   `/var/log/cloud-init-output.log`, `/var/log/cloud-init.log`,
   `/var/log/cfn-init.log` and `/var/log/cfn-init-cmd.log`, `/opt/backend/.env`,
   `migrate.env` and `db-bootstrap.env`, the bootstrap's, the migrate step's and
   the service's journals, and the service's log file. A match fails the deploy,
   naming the secret and the file, never printing the value.

**Bootstrap hygiene.** UserData runs with `set -euo pipefail` and never with
`set -x`, and holds no secret to begin with. `deploy.sh`, `db-bootstrap.sh`,
`smoke.sh`, and on the instance `db-bootstrap.sh`, `start.sh` and `migrate.sh`,
keep each secret in a shell variable: it reaches a process on a pipe, through a
process substitution or in that one process's environment — never as a
command-line argument, never in a file (the smoke's cookie jar, in a private
temporary directory removed on exit, is the one exception) — and is never
printed. Never run them with `bash -x`.

### An existing stack

The move to SQL Server replaces the database engine, and with it the instance;
the application holds no data that must be kept (ADR 0015). An existing stack
on the previous engine does not update into this template: delete it
(`cleanup.sh`) and deploy a fresh stack.

---

## Prerequisites

1. **Existing VPC in ap-southeast-1** with:
   - 2 public subnets (different AZs) for ALB; the instance's (public subnet 1)
     reaches the internet through the Internet Gateway, to install the SQL
     Server tools and fetch the RDS CA bundle at boot
   - 2 private subnets (different AZs) for RDS/Redis
   - Internet Gateway attached

2. **AWS CLI** configured, as a principal that may create the stack's
   resources (IAM, KMS, RDS, Secrets Manager, CloudWatch, SNS included), read RDS
   engine versions and orderable options, and create and write the S3 bucket the
   template is staged in — this principal becomes the database key's
   administrator (`KeyAdminArn`):

   ```bash
   aws configure
   # Set region: ap-southeast-1
   ```

   Plus `jq`, `curl`, `ssh`/`scp`, `openssl` and `base64` on the machine you
   deploy from.

3. **Application built** — the _integrated_ JAR (SPA + backend), from the repo
   root:

   ```bash
   make package     # == scripts/package.sh
   ```

   `./mvnw clean package` in `backend/` is a **backend-only** build: the
   `with-frontend` profile is off by default, so that JAR serves no SPA.
   `make package` builds `frontend/dist`, activates the profile with an explicit
   `-Dfrontend.dist.dir`, and fails if `BOOT-INF/classes/static/index.html` is
   missing from the artefact. `./deploy.sh` runs this for you when you answer yes
   to the JAR-deployment prompt.

---

## Deployment

### Option 1: Automated (Recommended)

```bash
./deploy.sh
```

It prompts for no password. In order, it:

1. checks that RDS resolves SQL Server `16.00` Standard Edition and that the
   instance class you chose is orderable for it, licence-included;
2. stages the template in S3 and creates the stack from it, and waits for it;
3. writes `<stack>-outputs.txt` (no secret value in it);
4. retrieves the SSH key into `<KeyName>.pem` (mode `0400`) when the stack created
   the key pair;
5. waits for the instance's boot — which installed `sqlcmd` and ran the database
   bootstrap — to finish, failing on a failed boot, then re-runs the bootstrap
   and fails unless the re-run changed nothing:
   `./db-bootstrap.sh --expect-no-op <stack>`;
6. on a yes to the JAR prompt, builds and deploys the integrated JAR, runs the
   migrate step, starts the service, and runs `./smoke.sh <stack>`, failing the
   deploy on any failed check; on a no, runs `./smoke.sh --leaks-only <stack>`,
   failing the deploy on a leaked secret.

The only User a deployment is seeded with is the Bootstrap Admin
(`AppBootstrapUsername`), whose first password is generated into
`AppBootstrapSecretArn`; existing accounts are never overwritten on restart.
After the first smoke that secret holds its current password:

```bash
aws secretsmanager get-secret-value --secret-id <AppBootstrapSecretArn> \
  --query SecretString --output text | jq -r .password
```

### Option 2: Manual

```bash
# 1. Get VPC info
./get-vpc-info.sh vpc-xxxxx

# 2. Configure parameters (no secret goes in it)
cp parameters.template.json parameters.json
# Edit parameters.json: VPC and subnet IDs, and KeyAdminArn — your deploying
# role's or user's ARN (aws sts get-caller-identity; for an assumed role, the
# role's ARN from aws iam get-role, path included)

# 3. Stage the template in S3: it is larger than --template-body accepts
BUCKET=cfn-templates-$(aws sts get-caller-identity --query Account --output text)-ap-southeast-1
aws s3 cp infrastructure.yaml s3://$BUCKET/spring-backend/infrastructure.yaml

# 4. Deploy stack
aws cloudformation create-stack \
  --stack-name spring-backend \
  --template-url https://$BUCKET.s3.ap-southeast-1.amazonaws.com/spring-backend/infrastructure.yaml \
  --parameters file://parameters.json \
  --capabilities CAPABILITY_NAMED_IAM \
  --region ap-southeast-1

# 5. Wait for completion (20-40 minutes: RDS for SQL Server is slow to create)
aws cloudformation wait stack-create-complete \
  --stack-name spring-backend \
  --region ap-southeast-1

# 6. Retrieve the SSH key (below), then confirm the boot's database bootstrap
#    and that a re-run is a no-op
./db-bootstrap.sh --expect-no-op spring-backend

# 7. Deploy the application (below), then the post-deploy smoke
./smoke.sh spring-backend
```

---

## SSH Key Management

### CloudFormation Creates Key (Recommended)

Set in `parameters.json`:

```json
{
  "ParameterKey": "CreateKeyPair",
  "ParameterValue": "true"
}
```

**Retrieve private key after deployment:**

```bash
KEY_PAIR_ID=$(aws cloudformation describe-stacks \
  --stack-name spring-backend \
  --region ap-southeast-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`KeyPairId`].OutputValue' \
  --output text)

aws ssm get-parameter \
  --name /ec2/keypair/${KEY_PAIR_ID} \
  --with-decryption \
  --query Parameter.Value \
  --output text > spring-backend-key.pem

chmod 400 spring-backend-key.pem
```

### Use Existing Key

Set in `parameters.json`:

```json
{
  "ParameterKey": "CreateKeyPair",
  "ParameterValue": "false"
},
{
  "ParameterKey": "KeyName",
  "ParameterValue": "my-existing-key"
}
```

---

## Deploy Application

The instance bootstraps the database at boot, so the JAR can be deployed as soon
as the stack is up and the boot has finished (`./db-bootstrap.sh <stack>` waits
for it). Starting the migrate step re-runs the bootstrap first.

```bash
# Get EC2 IP
EC2_IP=$(aws cloudformation describe-stacks \
  --stack-name spring-backend \
  --region ap-southeast-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`EC2PublicIP`].OutputValue' \
  --output text)

# Copy JAR (paths assume you're in the infra/ directory)
scp -i spring-backend-key.pem \
  ../backend/target/backend-0.0.1-SNAPSHOT.jar \
  ec2-user@$EC2_IP:/tmp/backend.jar

# Migrate (as the owner login), then start the application only if that succeeded
ssh -i spring-backend-key.pem ec2-user@$EC2_IP << 'ENDSSH'
set -euo pipefail
sudo mv /tmp/backend.jar /opt/backend/backend.jar
sudo chown springboot:springboot /opt/backend/backend.jar
sudo systemctl restart backend-migrate
sudo systemctl start backend
sudo systemctl enable backend
ENDSSH

# Then check it: health, TLS, SCIM and fixtures, alarms and secret leaks
./smoke.sh spring-backend
```

---

## Test Application

`./smoke.sh` covers this end to end. By hand: the Bootstrap Admin's password is
in `AppBootstrapSecretArn` (see [Deployment](#deployment)). If you replace a
seeded password by hand, write the new one back to that secret, or the next
`smoke.sh` cannot sign in.

```bash
# Get ALB URL
ALB_URL=$(aws cloudformation describe-stacks \
  --stack-name spring-backend \
  --region ap-southeast-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`LoadBalancerURL`].OutputValue' \
  --output text)

# Test health
curl $ALB_URL/actuator/health

# Every unsafe request, Login included, sends the session's CSRF token in the
# header GET /api/auth/csrf names. Login replaces the token, so fetch a fresh
# one before each unsafe request.
post() {
  local csrf; csrf=$(curl -s -b cookies.txt -c cookies.txt $ALB_URL/api/auth/csrf)
  curl -X POST "$ALB_URL$1" -b cookies.txt -c cookies.txt \
    -H "Content-Type: application/json" \
    -H "$(echo "$csrf" | jq -r .headerName): $(echo "$csrf" | jq -r .token)" \
    -d "$2"
}

# Login as the Bootstrap Admin. Its seeded password must be replaced first:
# until then the session reaches only the password change, which ends every
# session, so log in again with the new password.
post /api/auth/login '{"username":"admin","password":"YOUR_BOOTSTRAP_PASSWORD"}'
post /api/auth/change-password \
  '{"currentPassword":"YOUR_BOOTSTRAP_PASSWORD","newPassword":"A_NEW_PASSWORD"}'
post /api/auth/login '{"username":"admin","password":"A_NEW_PASSWORD"}'

# Test authenticated endpoint
curl $ALB_URL/api/count -b cookies.txt
```

---

## Operational telemetry

The service publishes Prometheus metrics at `/actuator/prometheus`: request
traffic, latency histograms and error class for every SCIM endpoint and for
Login, plus saturation gauges for the database pool (`hikaricp_connections_*`),
Tomcat's request threads (`tomcat_threads_*`), Redis commands (`lettuce_*`) and
the scheduled jobs (`app_job_*`). Requests are tagged by route template, method,
status and status class (`outcome`), SCIM resource type, SCIM `scimType` and
connector id, and never by `userName`, `externalId`, resource id, filter text or
token value.

### Who can read it

- **A session holding `ops:read` only.** Every actuator path except
  `/actuator/health` needs the `ops:read` Permission, which the Monitoring Role
  holds and nothing else — give the Prometheus scraper's account that Role alone. A
  session without it gets `403` (an Account admin's included), a caller with no
  session `401`.
- **Never a connector token.** The SCIM bearer chain covers `/scim/v2/**` only, so
  `Authorization: Bearer <connector token>` on `/actuator/prometheus` is not a
  credential there and gets `401`.
- `/actuator/health` stays public: it is the ALB's health check.

### Keep it off the internet

In this stack the ALB forwards every path to port 8080, so the scrape is reachable
from the internet. It is behind `ops:read` there, but the metrics surface belongs on the
internal network. Move actuator to its own port, which only the VPC can reach:

```bash
# in the service's environment on the EC2 instance
MANAGEMENT_SERVER_PORT=9090
```

With that set, port 8080 stops serving `/actuator/**`, and port 9090 serves it
**still behind `ops:read`**. The port adds a network boundary; it does not
replace the access rule. Two things change with it:

- **The ALB health check moves too.** Set the target group's `HealthCheckPort` to
  `9090`, keep `HealthCheckPath: /actuator/health`, and allow 9090 from the ALB
  security group. Otherwise every target reports unhealthy.
- **Scrapers must be inside the VPC.** Allow 9090 in the EC2 security group from
  the monitoring host's security group only, never from `0.0.0.0/0`. Do not add a
  listener rule that forwards to 9090.

`infrastructure.yaml` does not apply any of this yet. Change it, and review it
against the live stack, before you rely on the internal port.

### Scraping

A scrape presents the session cookie of an account holding `ops:read` — the
Monitoring Role's, and nothing more (`POST /api/auth/login` as that account,
then send the returned cookie). Sessions end after 15 minutes idle and 8 hours
absolute, so a long-running Prometheus has to log in again. No machine
credential for metrics exists yet.

With the internal port set, the login still goes to the application port, because
`/api/auth/login` is served only there. The same session cookie is then accepted on
port 9090. The session lives in Redis, and the management port reads it through the
application's own session filter (`ManagementSessionConfiguration`).

### Alerts

The alert rules are code: `backend/ops/prometheus/alerts.yaml`. Load them with
`rule_files:` in the Prometheus that scrapes the service. Each rule carries its
own runbook text:

| Alert                                         | Fires on                                                                         | Usually means                                                                                                                                        |
| --------------------------------------------- | -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ScimAuthenticationFailuresSustained`         | sustained SCIM `401`                                                             | a connector's token expired or was revoked, or probing                                                                                               |
| `LoginAuthenticationFailuresSustained`        | sustained Login `401`                                                            | a guessing campaign spread across accounts                                                                                                           |
| `ScimPreconditionFailuresSustained`           | sustained SCIM `412`                                                             | writers colliding on a stale `If-Match` (writes without `If-Match` apply unconditionally; see the `scim:unconditional_writes:rate1h` recording rule) |
| `ScimUniquenessConflictsSustained`            | sustained SCIM `409`                                                             | a connector re-creating identities it believes are missing                                                                                           |
| `DormancyJobFailed` / `DormancyJobNotRunning` | the dormancy job throws, or has not succeeded for 26 h                           | dormant accounts are not being locked, nor their Roles revoked                                                                                       |
| `EpicJwksFetchFailing`                        | `epic.outbound{call="jwks"}` errors persisting 5 min                             | Epic's `id_token` keys are unreachable (timeouts or Epic `5xx`): launches land at the unavailable notice; password Login is unaffected               |
| `EpicEndpointUnavailable`                     | `epic.outbound{call="token"\|"discovery"}` errors persisting 5 min               | Epic's token or discovery endpoint is unreachable: launches land at the unavailable notice; password Login is unaffected                             |
| `EpicClientCredentialRefused`                 | any `epic.login.failed_calls{call="token",error_category="cert/auth"}` in 15 min | Epic refused our client credential (`invalid_client`, assertion rejected): a signing key or the registration is wrong                                |
| `EpicLoginRefusalsSurge`                      | `login{method="sso",outcome="refused"}` above 3/min for 10 min                   | a broken integration (`ISS_MISMATCH`), unprovisioned Practitioner IDs, or replayed launches; the `reason` label says which                           |

The thresholds are starting points. Tune them against a week of normal traffic.
The dormancy job publishes its series under `job="dormancy"` from startup.
With Epic Login on, so does the outbound client: `epic_outbound_seconds` (a timer)
and `epic_outbound_errors_total` (calls that got no answer or a `5xx`), each
tagged `call` = `discovery`, `jwks` or `token`, exist at zero from startup, so
`EpicJwksFetchFailing` — `increase(epic_outbound_errors_total{call="jwks"}[5m]) > 0`
held for 5 minutes — sees the first error. The JWKS is fetched only when an
`id_token` names a key the kept JWKS lacks, so the alert fires on real launches
failing, not on a background poll. `EpicEndpointUnavailable` reads the same series
for the two calls every launch makes. The Login meters, `login_total` (every
Login that ended, password and Epic alike, by `method` — `password` or `sso` —
`outcome` and `reason`) and `epic_login_failed_calls_total` (each failed Epic call
that ended a Login, by `call` and `error_category`), exist at zero from startup
whether or not Epic Login is on; `EpicLoginRefusalsSurge` reads
`login_total{method="sso",outcome="refused"}`. With Epic Login off the outbound series do not
exist and the outbound rules match nothing.
`DormancyJobNotRunning` measures from
the last success **or the last restart**, so an instance that restarts more often
than daily masks a stuck job. Alert on restarts separately if that happens.

`OperationalTelemetryIntegrationTests` checks every selector in the rule file
against a real scrape, so renaming a metric or tag fails the backend build instead
of silently disarming an alert.

---

## Parameters Reference

| Parameter                  | Description                                     | Required | Default                 |
| -------------------------- | ----------------------------------------------- | -------- | ----------------------- |
| VpcId                      | Existing VPC ID                                 | Yes      | -                       |
| PublicSubnet1Id            | Public subnet 1                                 | Yes      | -                       |
| PublicSubnet2Id            | Public subnet 2                                 | Yes      | -                       |
| PrivateSubnet1Id           | Private subnet 1                                | Yes      | -                       |
| PrivateSubnet2Id           | Private subnet 2                                | Yes      | -                       |
| CreateKeyPair              | Create key pair                                 | No       | true                    |
| KeyName                    | Key pair name                                   | No       | spring-backend-key      |
| InstanceType               | EC2 type                                        | No       | t3.small                |
| SSHLocation                | CIDR allowed to SSH to the instance             | No       | 0.0.0.0/0 — narrow it   |
| DBInstanceClass            | SQL Server SE class (see "Engine, edition…")    | No       | db.t3.xlarge            |
| DBAllocatedStorage         | RDS storage, GB (20–100)                        | No       | 20                      |
| DBName                     | Database the bootstrap creates                  | No       | backend                 |
| DBMasterUsername           | RDS master username (password RDS-managed)      | No       | dbmaster                |
| DBOwnerLogin               | Owner login (`db_owner`, migrations)            | No       | backend_owner           |
| DBAppLogin                 | App login (member of `backend_app`)             | No       | backend_app_login       |
| DBRetentionLogin           | Retention login (`backend_audit_retention`)     | No       | backend_retention_login |
| KeyAdminArn                | Deploying role/user; administers the DB key     | Yes      | - (deploy.sh fills it)  |
| AlarmEmail                 | Email subscribed to the alarm topic             | No       | (empty)                 |
| RedisNodeType              | ElastiCache node type                           | No       | cache.t3.micro          |
| AppBootstrapUsername       | Bootstrap Admin's username (password generated) | No       | admin                   |
| AppEpicEnabled             | Epic Login switch (`APP_EPIC_ENABLED`)          | No       | false                   |
| AppEpicFhirBase            | Epic FHIR base URL, https                       | If Epic  | (empty)                 |
| AppEpicOauthIssuer         | Epic OIDC issuer, https                         | If Epic  | (empty)                 |
| AppEpicClientId            | Epic client id (NoEcho)                         | If Epic  | (empty)                 |
| AppEpicRedirectUri         | Registered callback URL, https                  | If Epic  | (empty)                 |
| AppEpicClientKey           | Active EC P-384 key, one-line PEM (NoEcho)      | If Epic  | (empty)                 |
| AppEpicClientKeyId         | Active key's `kid`                              | If Epic  | (empty)                 |
| AppEpicClientNextKey       | Next key, one-line PEM (NoEcho)                 | No       | (empty)                 |
| AppEpicClientNextKeyId     | Next key's `kid`, set with the next key         | No       | (empty)                 |
| AppEpicConnectTimeout      | Outbound Epic connect timeout                   | No       | (empty) = 2s            |
| AppEpicReadTimeout         | Outbound Epic read timeout                      | No       | (empty) = 5s            |
| AppEpicMfaEvidenceRequired | Require MFA evidence in `amr` (D17)             | No       | false                   |
| AppEnvironment             | `service.environment` on every log record       | No       | production              |
| LogRetentionDays           | Retention of the `/<stack>/backend` log group   | No       | 90                      |

The `AppEpic*` parameters become the `APP_EPIC_*` variables of
`backend/README.md`'s "Epic Login" section; with `AppEpicEnabled=false` none is
read. No default carries a value, and with the switch on the service refuses to
start until every required one is set and well formed. The two keys and the
client id are `NoEcho`, and the stack writes them into `EpicClientSecretArn`,
which `start.sh` reads at every start; they never reach UserData or a file on the
instance (see [Database credentials](#database-credentials)). The other
`AppEpic*` values are not secret and are written into `/opt/backend/.env`. Give
each key as **one line with no line breaks** — the armour and the base64 run
together, e.g. `awk 'NF {printf "%s", $0}' key.pem` — because the stack splices
it into the secret's JSON, which a line break would break; for the same reason
none of the three may contain `"` or `\`. Never commit a key or a filled-in
parameters file.

### Epic outbound timeouts

`AppEpicConnectTimeout` and `AppEpicReadTimeout` become `APP_EPIC_CONNECT_TIMEOUT`
and `APP_EPIC_READ_TIMEOUT`: the connect and read timeouts of every call the
service makes to Epic — discovery, the JWKS and the token call, all through one
client (ADR 0013, D25). Each is a duration such as `2s` or `1500ms`; left empty
they are 2 and 5 seconds, and a zero or negative value refuses startup. A call
that times out, like one Epic answers `5xx`, sends the clinician to the login
page's "temporarily unavailable" notice rather than the refused one, and is an
`ERROR` record under `error.category` `network` (or `server`). The token call is
never retried, so a read timeout longer than Epic's own processing time is what
keeps a slow Epic from looking unavailable; raise it rather than lowering it if
clinicians see the notice while Epic is merely slow. There is no circuit breaker:
the edge throttle on the callback (above) bounds the load on Epic.

### Signing-key promotion

The service signs Epic client assertions with the **active** key
(`AppEpicClientKey` / `AppEpicClientKeyId`) and publishes it, plus the optional
**next** key (`AppEpicClientNextKey` / `AppEpicClientNextKeyId`), at the public
`GET /api/auth/epic/jwks.json`, where Epic fetches it. The next key is published
but never signs. The service has no expiry or rotation logic of its own: a key is
published for exactly as long as configuration names it, and every step below is
an operator's redeploy (`/docs/adr/0013-epic-login.md`, D14).

A redeploy here means changing the values the service starts with and
restarting it. The keys and the `kid`s travel separately:

- **The keys** (`AppEpicClientKey`, `AppEpicClientNextKey`) live in
  `EpicClientSecretArn`. Update the stack's parameters — which rewrites the
  secret and nothing else — and `start.sh` reads the new values at the next
  restart.
- **The `kid`s** (`AppEpicClientKeyId`, `AppEpicClientNextKeyId`) are in
  `/opt/backend/.env`, which UserData writes only when the instance is created.
  Set them in **both** places: the stack parameters (so a replacement instance
  gets them) and `/opt/backend/.env` on the running instance.

Then `sudo systemctl restart backend`. Each restart's startup
record carries `app.epic.client_key_id` and `app.epic.client_next_key_id` (the
`kid`s, never the keys), so every step leaves a record in the log.

1. **Create the next key around mid-period**, under a new `kid` — a date makes a
   good one, e.g. `epic-2027-04`:

   ```bash
   openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -out next.pem
   awk 'NF {printf "%s", $0}' next.pem   # the one-line value for the parameter
   ```

2. **Publish it.** Set `AppEpicClientNextKey` and `AppEpicClientNextKeyId` and
   redeploy. Confirm both `kid`s are served, the active one first:

   ```bash
   curl -s https://<host>/api/auth/epic/jwks.json | jq -r '.keys[].kid'
   ```

   Leave it published long enough for Epic to have fetched the new document
   before the next step, since Epic may cache our JWKS.

3. **Promote it by redeploying with it as the active key**: move the next key's
   values into `AppEpicClientKey` / `AppEpicClientKeyId`, and put the old active
   key into `AppEpicClientNextKey` / `AppEpicClientNextKeyId`, so it stays
   published while assertions Epic may still be checking against it age out.
   From this restart every assertion carries the new `kid`.

4. **Remove the old key from configuration**: clear `AppEpicClientNextKey` and
   `AppEpicClientNextKeyId` (both together — the service refuses to start with
   one set and not the other) and redeploy. The JWKS then lists the active key
   alone. Destroy the old key's PEM.

To withdraw a compromised key, skip the waiting: promote the next key (or a
freshly created one) and remove the compromised key in a single redeploy.

The keys are environment variables for now; the target is an AWS KMS-held key
that never leaves KMS, which is open work (`/docs/adr/0013-epic-login.md`).

---

## Common Tasks

### View Logs

The service writes ECS JSON to stdout (journald) **and** to a rolling file,
`/var/log/backend/backend.json` (`LOG_FILE` in `/opt/backend/.env`; directory
owned by `springboot`, mode `0750`). The file rolls daily or at 50 MB, keeps 14
days and never exceeds 1 GB on disk. The CloudWatch agent on the instance ships
that file into the log group `/<stack name>/backend`, one stream per instance,
kept for `LogRetentionDays`. The agent's configuration is
`/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json`. The service
itself never sends logs over the network.

Every record carries `service.name` (`backend`), `service.version` (the built
version), `service.environment` (`AppEnvironment`), a `trace.id` / `span.id` for
the request or scheduled-job run that emitted it, and an `@timestamp` in
Singapore time (`+08:00`). Search CloudWatch Logs Insights by `trace.id` to read
one request end to end.

```bash
ssh -i spring-backend-key.pem ec2-user@$EC2_IP
sudo journalctl -u backend -f              # stdout
sudo journalctl -u backend-db-bootstrap    # the database bootstrap's runs
sudo tail -f /var/log/backend/backend.json # the file the agent ships
```

### Restart Application

```bash
ssh -i spring-backend-key.pem ec2-user@$EC2_IP "sudo systemctl restart backend"
```

### Update Application

```bash
# Build the integrated JAR (SPA + backend), from the repo root
(cd .. && make package)

# Confirm the SPA is in the artefact (package.sh already asserts this)
unzip -Z1 ../backend/target/backend-0.0.1-SNAPSHOT.jar BOOT-INF/classes/static/index.html

# Copy and restart (from infra/)
scp -i spring-backend-key.pem \
  ../backend/target/backend-0.0.1-SNAPSHOT.jar \
  ec2-user@$EC2_IP:/tmp/backend.jar

# The migrate step (after the database bootstrap, a no-op) runs against the new
# JAR first; the service starts only if it succeeded (see "Migrate step").
ssh -i spring-backend-key.pem ec2-user@$EC2_IP << 'ENDSSH'
set -euo pipefail
sudo systemctl stop backend
sudo mv /tmp/backend.jar /opt/backend/backend.jar
sudo chown springboot:springboot /opt/backend/backend.jar
sudo systemctl restart backend-migrate
sudo systemctl start backend
ENDSSH
```

### Check ALB Target Health

```bash
TG_ARN=$(aws elbv2 describe-target-groups \
  --names spring-backend-TG \
  --region ap-southeast-1 \
  --query 'TargetGroups[0].TargetGroupArn' \
  --output text)

aws elbv2 describe-target-health \
  --target-group-arn $TG_ARN \
  --region ap-southeast-1
```

### Delete Stack

```bash
./cleanup.sh

# Or manually:
aws cloudformation delete-stack \
  --stack-name spring-backend \
  --region ap-southeast-1
```

### Inspect the Stack

```bash
aws cloudformation describe-stack-events --stack-name spring-backend \
  --region ap-southeast-1 --max-items 20

aws cloudformation describe-stacks --stack-name spring-backend \
  --region ap-southeast-1 --query 'Stacks[0].Outputs' --output table
```

---

## Troubleshooting

### Health Check Failing

Check application status:

```bash
ssh -i spring-backend-key.pem ec2-user@$EC2_IP
sudo systemctl status backend
sudo journalctl -u backend -n 100
```

Check target health:

```bash
aws elbv2 describe-target-health --target-group-arn $TG_ARN
```

**Common causes:**

- Application not running: `sudo systemctl start backend`
- Wrong health check path (should be `/actuator/health`)
- Security group blocking ALB → EC2:8080

### Can't Access via ALB

1. Check ALB DNS is correct
2. Verify security groups:
   - ALB SG allows 80 from internet
   - EC2 SG allows 8080 from ALB SG
3. Check target group shows "healthy"

### Database Connection Error

```bash
ssh -i spring-backend-key.pem ec2-user@$EC2_IP

# The URL and the secret ARNs the start script reads (no password is in here)
sudo grep -E '^(DATABASE_|AWS_REGION)' /opt/backend/.env

# Did start.sh read its secrets? An AccessDenied or a missing field shows here
sudo journalctl -u backend -n 50 --no-pager
```

From your own machine, `./db-bootstrap.sh <stack>` doubles as the connection
test: the bootstrap signs in as each login over validated TLS, and resets the
password of one whose secret changed (a `db-init: change: reset the password`
line). Its journal on the instance is `sudo journalctl -u backend-db-bootstrap`.

- `certificate verify failed` (from `sqlcmd`) or `PKIX path building failed` /
  `trustAnchors parameter must be non-empty` (from the service's driver): the RDS
  CA bundle or the trust store is missing or stale, or the trust-store password
  did not reach the driver. Check `/opt/backend/rds-ca-bundle.pem` and
  `/opt/backend/rds-truststore.p12` exist (`sudo keytool -list -storetype PKCS12
-keystore /opt/backend/rds-truststore.p12 -storepass:env P` with `P` exported
  from `DBTrustStoreSecretArn`, in a shell you then close).
- `keystore password was incorrect`: the store was built under another password
  than `DBTrustStoreSecretArn` holds now; rebuild it as UserData does.
- `Login failed for user`: the login's password differs from its secret — re-run
  `./db-bootstrap.sh <stack>`.

### Can't Retrieve SSH Key

- Verify `CreateKeyPair: true` in parameters
- The private key stays readable in SSM (`/ec2/keypair/<KeyPairId>`) for as
  long as the key pair exists, so re-run the retrieval above; deleting the stack
  deletes both
- If the key pair is gone, deploy a new stack or use an existing key
