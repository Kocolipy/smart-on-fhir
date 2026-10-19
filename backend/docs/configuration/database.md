---
type: Configuration Reference
title: Database logins
description: The three database logins and their grants, the connection pools, where migrations run, and the local SQL Server setup.
tags: [sql-server, database, migrations, configuration]
---

# Database logins

The service reaches SQL Server as **three logins**, each holding only its own
privileges, and nothing switches role on a connection (ADR 0013, "Runtime
model"; `NoRoleSwitchTest`, in the `arch` tests, holds that no source
or configuration file issues `SET ROLE`):

| Login               | Variables                                                    | Used by                                                                     | Member of                 |
| ------------------- | ------------------------------------------------------------ | --------------------------------------------------------------------------- | ------------------------- |
| **app login**       | `DATABASE_USERNAME`, `DATABASE_PASSWORD`                     | the main pool (`app`) and the audit append pool (`audit-append`)            | `backend_app`             |
| **retention login** | `DATABASE_RETENTION_USERNAME`, `DATABASE_RETENTION_PASSWORD` | the audit retention job's own pool (`retention`) and nothing else           | `backend_audit_retention` |
| **owner login**     | `DATABASE_OWNER_USERNAME`, `DATABASE_OWNER_PASSWORD`         | the migrations, and nothing else: never the running service in a deployment | `db_owner`                |

`DATABASE_URL` is the one database all three reach. **Neither it nor any of the six
login variables has a default** — this repository is public — so a missing one
fails startup. The test JVMs drop all of them (`pom.xml`).

- **`backend_app`** holds full DML on the tables whose lifecycle the application
  owns (the `scim_*` directory tables) and narrower grants
  where a table's rule is narrower: `SELECT`/`INSERT` only on `scim_tombstones`,
  `audit_events` and `patients` (V2; the insert is the development patient
  seeder's, and nothing changes or removes a patient), no `UPDATE` on
  `scim_user_password_history`, and
  `SELECT` only on `scheduled_job_locks` and `scim_seed_locks` (a locking read
  needs no more), so the app login cannot change or remove
  the row a job or seeding serializes on. An `UPDATE` or `DELETE` of a recorded event from
  application code is refused by the server. The migrations' `GRANT` statements
  are the exact list; a later migration that adds a table the application writes
  must grant `backend_app` on it, and one that drops a table drops its grants
  with it (V3), so it needs no `REVOKE`. A refusal is SQL Server error 229.
- **`backend_audit_retention`** holds `SELECT`/`DELETE` on `audit_events` and
  nothing else — no `UPDATE`. Only the audit retention adapter reaches the
  retention pool.

Beside the grants the audit table carries an `INSTEAD OF UPDATE, DELETE` trigger
that refuses **every** `UPDATE`, from every principal, and every `DELETE` unless
the caller is a member of `backend_audit_retention` and not of `db_owner`, with
error 50001. That second half is what refuses the owner login, which owns the
database — so the grants do not bind it — and a sysadmin, which enters as its
owner. Append-only therefore survives a console session or a misused owner
credential. `AuditAppendOnlyIntegrationTests` connects as each login and asserts
each refusal.

The pools: how each reaches the database is one module's, `DatabaseConnections`
(`database.config`): the URL, the driver, and every driver property under
`spring.datasource.hikari.data-source-properties` — set in
`src/main/resources/database.yaml`: `encrypt=true`, `sslProtocol=TLSv1.2` and
`loginTimeout=15`, and on the deployed stack the driver-scoped trust store's
password, which arrives from the environment as
`SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_TRUSTSTOREPASSWORD` while
`DATABASE_URL` names the store (`/infra/docs/database-encryption.md`, "TLS to the database") —
reach the main, audit append
and retention pools alike, and the migrate step's owner connection too
(`MigrationConnection`). Set such a property there, for the service and the
migrate step both, and never on one pool. A pool states only its name, login,
size and connection timeout. The main pool binds every
`spring.datasource.hikari.*` setting as before, and is named `app`; the retention
pool holds two connections and binds its login, and only its login, from
`app.datasource.retention.*`. Both wait at most 10 s for a
connection, and both appear by name in the `db` health indicator
(`dataSource`, `auditRetentionDataSource`) and in the `hikaricp_*` series
(`pool="app"`, `pool="retention"`). The retention pool starts on first use — its
first run, or the first health check.

A retention run takes the scheduled job's lock on the main pool, as the app
login, and holds it for the whole run; its deletes commit in transactions of the
retention pool's own transaction manager inside that run (ADR 0005).

## Where migrations run

Flyway runs as the owner login, and **only in the `local`, `test` and `migrate`
profiles**; every other profile — a deployment's included — runs with it off,
and `ddl-auto: validate` still fails startup on a schema that drifted.
`MigrationProfilesTests` holds that, and that the deployed configuration reads no
owner credential.

- **A deployment** runs the **migrate step** before the service: the same JAR,
  started at its migrate entry point, with no web server, and exits.

  ```bash
  java -Dloader.main=com.example.backend.database.config.MigrationStep \
      -cp backend.jar org.springframework.boot.loader.launch.PropertiesLauncher
  ```

  It needs `DATABASE_URL`, `DATABASE_OWNER_USERNAME`, `DATABASE_OWNER_PASSWORD`
  and the two other logins' **names** (`DATABASE_USERNAME`,
  `DATABASE_RETENTION_USERNAME`) — no other password — and whatever
  `spring.datasource.hikari.data-source-properties.*` the service is given, so
  its connection is opened as the pools' are. A failed migration exits
  non-zero, and the deploy does not start the service (`/infra/docs/database-boot.md`,
  "Migrate step"). The service is then given the app and retention logins only.

- **Locally**, `SPRING_PROFILES_ACTIVE=local` (`.env.example`) migrates at
  startup; **the tests** migrate each context's database as the owner login
  (`ContainerTestConfiguration`).

The migrations learn the app and retention logins' names as the placeholders
`app_login` and `retention_login`, and `V1` creates a database user `FOR LOGIN`
each and makes it a member of its role, quoting the name with `QUOTENAME`. `MigrationLoginNames` refuses a name that does not match
`^[a-z][a-z0-9_]{2,30}$`, or the two naming one login, before Flyway runs a
statement.

## Local setup

`compose.yaml` runs **SQL Server 2022** (CU27, Developer Edition, pinned by tag
and digest, `linux/amd64`) on `localhost:1433`, capped at 2 GB, with a health
check, beside Redis. Its one-shot `sqlserver-init` service, in a profile of its own
so a plain `up` leaves it out, is run in the foreground by `make infra-up` once SQL
Server is healthy — `docker compose run --rm sqlserver-init`, whose exit status is
the init's, since `up --wait` does not wait for a one-shot to finish
(`scripts/local-db-init.sh` and `local-db-init.sql`): it creates the
`backend` database with its creation options — collation
`Latin1_General_100_BIN2_UTF8`, compatibility level 160, `READ_COMMITTED_SNAPSHOT`
and `ALLOW_SNAPSHOT_ISOLATION` on, none of which a migration can set — and the
owner, app and retention logins from `.env`, and makes the owner login a member
of the database's `db_owner` role — not the database's owner, because RDS's master
user, which runs the deployed bootstrap, cannot hand a database to another login,
and local, test and deployed databases are built alike. It is idempotent: it
leaves what exists, and resets the password of a login whose `.env` password no
longer signs in, so `make infra-up` after changing `.env` applies it and a run
that finds everything in place changes nothing. Its T-SQL is the deployed
bootstrap's, line for line (`infra/check-bootstrap-parity.sh`). It echoes
no secret: values reach `sqlcmd` as scripting variables from the environment,
never as arguments, and `sqlcmd` runs without `-e`. The roles, the app and
retention logins' database users and their grants come from `V1`, so the init
grants nothing. A deployment creates the database and the logins with the same
T-SQL, run on its instance as the RDS master user (`infra/docs/database-boot.md`, "Database
bootstrap").

The local server keeps its default collation and only the database is collated
`Latin1_General_100_BIN2_UTF8`. The deployed RDS server is collated
`Latin1_General_100_BIN2_UTF8` too (`infra/docs/database-engine.md`, "Collation and time
zone"), but on this image a server collated `BIN2_UTF8`
cannot load the internal procedures behind `sp_getapplock`, which Flyway takes to
guard every migration. The service's own seed lock is a row lock and needs
neither (ADR 0013, "Addendum (2026-10-16)").

The `.env` variables it reads:

| Variable                                                     | What it is                                                                                                                                                                                                          |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MSSQL_SA_PASSWORD`                                          | The local server's `sa` password, read by compose for the server and its init only; the backend never sees it. SQL Server's policy applies: 8+ characters from three of upper case, lower case, digits and symbols. |
| `DATABASE_URL`                                               | `jdbc:sqlserver://localhost:1433;databaseName=backend`. No default.                                                                                                                                                 |
| `DATABASE_OWNER_USERNAME`, `DATABASE_OWNER_PASSWORD`         | The owner login, created by the init and made a member of the database's `db_owner` role.                                                                                                                           |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD`                     | The app login.                                                                                                                                                                                                      |
| `DATABASE_RETENTION_USERNAME`, `DATABASE_RETENTION_PASSWORD` | The retention login.                                                                                                                                                                                                |

Login names must match `^[a-z][a-z0-9_]{2,30}$` and be three different names; a
password must hold no quote. The local server's certificate is self-signed, so
the `local` profile alone sets `trustServerCertificate=true`; everywhere else the
certificate is validated, and `TrustServerCertificateTest` fails the build if
that setting appears in any other profile, resource or deployment script. The
connection is encrypted either way (`database.yaml`).

`make dev` checks that SQL Server answers at the host and port of
`DATABASE_URL` (1433 by default) before it starts anything.
