---
type: Deployment Reference
title: Boot, database bootstrap and migrate step
description: What UserData runs at boot, how the bootstrap creates the database and logins idempotently, and the migrate step that keeps the owner login out of the service.
tags: [userdata, bootstrap, migrations]
---

# Boot, database bootstrap and migrate step

## Boot sequence

UserData runs once, when the instance is created, with `set -euo pipefail` and
never `set -x`:

1. **The SQL Server command-line tools.** `sqlcmd` on the Microsoft ODBC Driver
   18 (`mssql-tools18`, `msodbcsql18`) from Microsoft's signed RHEL 9 package
   repository; `ACCEPT_EULA=Y` accepts their licence terms, which installing
   them requires. Then `cfn-init` writes the database bootstrap's three files and
   the secret-reading helper (`/opt/backend/secret.sh`) from the instance's
   `AWS::CloudFormation::Init` metadata, and UserData builds the RDS CA bundle
   and the driver-scoped trust store
   ([TLS to the database](database-encryption.md#tls-to-the-database)).
2. **The database bootstrap**, as the RDS master user
   ([Database bootstrap](#database-bootstrap)): UserData starts
   `backend-db-bootstrap.service`. A failed bootstrap fails the boot, and
   `deploy.sh` stops there.
3. **The migrate step** (`backend-migrate.service`, [Migrate step](#migrate-step)).
4. **The service** (`backend.service`), with the credentials `start.sh` resolves
   and `APP_DEV_FIXTURES_ENABLED=false`: no development fixture account is ever
   seeded (ADR 0013, "Admin and fixture seeding stay the service's"); the Bootstrap
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

## Database bootstrap

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
   logins differ, and that no password holds a quote (ADR 0013, as-2);
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
     database creation options ADR 0013 assigns to whatever creates the database,
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

**It echoes no secret** (ADR 0013, "The bootstrap does not echo"). No `set -x`;
`sqlcmd` runs without `-e`; the signing-in login's password reaches `sqlcmd` as
`SQLCMDPASSWORD`, and every other value as a `sqlcmd` scripting variable read
from its environment — `$(DATABASE_OWNER_PASSWORD)` in the SQL, never `-v` on a
command line, where `ps` would show it. `sqlcmd` does not expand a `$(` inside a
variable's value, so a generated password holding one is safe. Its output, in
the journal and the boot log, holds names and outcomes only, and the
[post-deploy smoke](post-deploy-smoke.md) scans both for every secret value.

**One T-SQL, two copies.** The SQL is, from `SET NOCOUNT ON` down, line for line
`backend/scripts/local-db-init.sql`, which builds the local database the same way
(the local server's `sa` in place of the master user). A stack cannot read a file
from this repository, so the text is held twice: `./check-bootstrap-parity.sh`
fails, with the difference, when the copies drift. `deploy.sh` runs it before
every deploy; run it yourself after changing either. The shell around the SQL
is small and differs by design (`sa` and `-C` locally, the master user and `-N`
here); every decision, the password reset included, is in the shared SQL.

## Migrate step

**The running service never holds the owner credential** (ADR 0013, ac-1). The
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
