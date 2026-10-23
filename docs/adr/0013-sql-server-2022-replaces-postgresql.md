# 13. SQL Server 2022 replaces PostgreSQL

Date: 2026-10-12

## Status

Accepted, and implemented (#62–#72): the backend runs on SQL Server 2022 locally
and in tests, and the deployed stack is RDS for SQL Server (#71). Some decisions
rested on verification spikes V1–V6 (2026-10-12), under "Verified behaviour". It
records a deviation from App-Standards' recommendation of PostgreSQL for production
(Standalone User Access Control Q6, User SSO Q5). This file states the current
position. Last revised 2026-10-23: the test JVMs share one SQL Server. History is
in git.

## Context

The organisation has mandated SQL Server as its database engine.
App-Standards recommends PostgreSQL for production, so moving off it is a
conscious deviation, made for **organisational mandate** and not for a technical
shortcoming of PostgreSQL.

The application is pre-production and holds no data that must be kept.

The move was reviewed on 2026-10-12 against the IM8 application control catalog and
against App-Standards (User Standalone, User SSO, Structured Logging). Every finding
of those reviews is resolved below, so the decision and its compliance conditions
are one record.

## Decision

### Scope

- **One engine.** SQL Server is the only database the backend supports.
- **SQL Server 2022, database compatibility level 160.** Mainstream support runs to
  January 2028 and extended support to January 2033; 2019 already receives security
  fixes only. Level 160 adds `IS [NOT] DISTINCT FROM`, which the SCIM filter SQL
  uses.
- **No data migration.** The schema is a single T-SQL `V1`.
  A fresh database is the only supported starting state.
- **RDS for SQL Server, Standard Edition, licence-included** in production:
  managed and AWS-native, without Express's 10 GB cap. **Developer
  Edition** locally and in tests, which is free for development and test use. The
  edition is a stakeholder sign-off, because Standard Edition is costly and does
  not run on the smallest instance classes.
- **x86_64 only** for local development (WSL2, Linux, CI). The SQL Server image has
  no arm64 build; Apple Silicon runs it only through slow, unsupported emulation,
  and is a documented limitation.

### Runtime model

- **Three logins.** The **owner login** runs migrations and nothing else. It is a
  member of `db_owner`, not the database's owner: RDS's master user, which creates
  the database, cannot hand it to another login (that needs `IMPERSONATE` on the
  login), so every environment — the deployed bootstrap, the local init and
  `ContainerTestConfiguration` — adds it to `db_owner`
  (`MigrationStepIntegrationTests.theOwnerLoginMigratesAsADbOwnerMemberNotAsTheDatabasesOwner`). The **app login** is the request pool's, a member of
  `backend_app` only. The **retention login** has its own small DataSource and
  transaction manager, is used only by the audit retention adapter, and is a member
  of `backend_audit_retention` only. The server enforces each login's privileges,
  and nothing switches identity on a connection, so nothing can leak between
  pooled uses of one.
- **The audit append pool connects as the app login** (ADR 0004, "Fail-open
  appends commit on a pool of their own"), from the same secret, never as the
  owner login.
- **A connector token's row lock sits with the SCIM resource locks** (added
  2026-10-13). A rotation and a revocation lock the token's row before reading it
  (`ScimConnectorTokenJpaRepository.lockById`, `WITH (UPDLOCK, ROWLOCK)`, taken only
  by `ScimWriteLockAdapter`), so of two concurrent rotations exactly one mints a
  replacement. It locks one row by primary key, so it needs no order, and must be
  the transaction's first read of the token for the waiting rotation to see the first one's replacement
  under read-committed snapshot.
- **The SCIM resource lock order is the application's, not the engine's** (added
  2026-10-14). A SCIM write locks every resource row it may advance before reading
  any of them, in one canonical order, so two writers whose sets overlap serialize
  instead of deadlocking. SQL Server under `UPDLOCK` locks rows as the scan reads
  them, before any sort, so an `ORDER BY` on a locking statement gives no lock
  order, and a multi-row lock would deadlock concurrent SCIM writes.
  `ScimWriteLockAdapter` therefore sorts the set itself (`ScimWriteLocks.ORDER`,
  `UUID#compareTo`) and locks one row by primary key per statement
  (`ScimResourceJpaRepository.lockResource`, `WITH (UPDLOCK, ROWLOCK)`), which the
  engine cannot reorder. The order deliberately differs from the engine's
  `uniqueidentifier` sort, so a statement that slid back to relying on it fails
  `ScimWriteLockAdapterIntegrationTests`. The cost is a round
  trip per locked row — a rename of a Group with two hundred members takes two
  hundred and one — accepted because a batched statement would be correct only on an
  engine that locks in sort order, and ordered chunks reintroduce the engine's order
  inside each chunk. A lock of one row by key needs no order: under
  `PESSIMISTIC_WRITE` Hibernate renders it as `WITH (UPDLOCK, HOLDLOCK, ROWLOCK)`.
- **Database creation options are set by whatever creates the database** (the
  deployed bootstrap, the local init, the test container), never by Flyway:
  `ALTER DATABASE … SET READ_COMMITTED_SNAPSHOT` cannot run inside a migration
  transaction. Flyway learns the login names through placeholders and never their
  passwords; it creates the database users `FOR LOGIN` and grants the roles.
- **`VARCHAR` with database collation `Latin1_General_100_BIN2_UTF8`.** UTF-8
  storage with code-point (byte) order, which the SCIM sort relies on. Equality and the unique
  constraints stay case-sensitive; case-insensitive uniqueness keeps comparing the
  application-normalized (NFKC + lower-case) columns, so it never depends on SQL
  Server's `LOWER` (#68). The collation is the **database's**, set by
  `CREATE DATABASE … COLLATE`; only the deployed server shares it ("Collation and
  the seed lock").
- **`READ_COMMITTED_SNAPSHOT ON` and `ALLOW_SNAPSHOT_ISOLATION ON`, with the audit
  listing at `SNAPSHOT`.** Readers do not block writers. The listing's count and page must see one consistent view; SQL Server's
  `REPEATABLE READ` does not give that, `SNAPSHOT` does.
- **Memberships are deleted explicitly, with no multi-path cascades.** SQL Server
  rejects two cascade paths into one table, so both member-side foreign keys of
  `scim_group_members` become `NO ACTION`, and the User and Group delete paths
  delete the resource's memberships in the same transaction before deleting the
  resource. Doing it in the application keeps the behaviour visible and testable.
  Every other cascade is single-path
  and stays.
- **Limits are in code points; Unicode-capable columns are ×4.** The API
  contract's limits count code points. A UTF-8 code point takes at most four bytes, so every
  `VARCHAR(n)` that carries a code-point limit becomes `VARCHAR(4n)`; ASCII-only
  enum-like columns keep `n`. Index keys stay under SQL Server's 1700-byte
  non-clustered limit.
- **Leading or trailing whitespace is refused on every unique or lookup key**, with
  `400 invalidValue`: User `userName`, User and Group `displayName`,
  `emails[].value`, `emails[].type` and `externalId`. Other strings keep their
  whitespace. SQL Server pads `=` comparisons and unique constraints even under a
  `BIN2` collation; refusing edge whitespace on every key keeps behaviour
  deterministic without SQL tricks. The same rule reaches authentication: a
  password Login whose username has edge whitespace is treated as an unknown
  account before any lookup, and an Epic `iss` or `sub` claim with edge whitespace
  is refused as an invalid token.
- **Token permissions are a child table**,
  `scim_connector_token_permissions(token_id, permission)`, keyed on both columns,
  with a CHECK limiting `permission` to the four directory Permissions. SQL Server
  has no arrays, and the child table keeps the constraint in the database.
- **The audit trail is append-only, enforced by a T-SQL trigger.** An `INSTEAD OF UPDATE,
DELETE` trigger on `audit_events` throws unless the caller is a member of
  `backend_audit_retention`, and refuses every `UPDATE` from every principal. The
  retention role holds **`SELECT, DELETE` only**, with no `UPDATE`, so the grant and
  the trigger agree. No principal may `UPDATE` an audit row: audit
  rows are immutable, and only aged-out rows can be deleted, only by the retention
  login. The app login is refused by its grants first; the owner login and any
  mis-granted login are refused by the trigger, which is why the trigger stays as
  defence in depth.
- **NUL (U+0000) is refused as input hygiene.** SQL Server can store NUL, but it is
  hostile in logs and terminals.
- **`OPTION (RECOMPILE)` on the audit listing's count and page statements.** Their
  catch-all `(@x IS NULL OR col = @x)` filters would otherwise share one cached plan
  across every filter combination. The listing is a low-volume administrative
  read, so the compile cost does not matter. Both stay whole constant statements.
- **The scheduled job lock is a per-job row** (ADR 0005), taken with
  `WITH (UPDLOCK, ROWLOCK, READPAST)`. Spike
  V3 verified that `READPAST` skips a held row under read-committed snapshot
  without waiting, so the `sp_getapplock` fallback is not needed. The seed lock
  is a row lock too ("Collation and the seed lock").
- **`audit_events` is clustered on `(occurred_at, id)`**, with a non-clustered
  primary key on `id`. A random-UUID clustered key fragments on every insert into
  the highest-volume table, while retention deletes by `occurred_at` and the
  listing orders by it, so both become range operations. Retention deletes in
  batches below SQL Server's lock-escalation threshold, one transaction per batch,
  so a long run does not block audit appends; retention is idempotent, and the next
  run completes a partial one.
- **An `IN` list a client sizes is bound in chunks.** SQL Server refuses a request
  carrying more than 2,100 parameters (error 8003), and Hibernate binds each
  element of a collection parameter as one. A statement whose `IN` list has no cap
  of its own is issued once per chunk of at most 2,000 ids (`BoundIdChunks`), in
  the caller's transaction: the version advance
  (`ResourceVersionStatements#advance`), which a rename, a delete or a membership
  change sizes by membership, and the Group adapter's membership read, which the
  listing sizes by the directory. A list a page bounds (`ScimPageRequest.MAX_COUNT`, 200) binds whole. Every row a version advance names is already locked, one row
  per statement in `ScimWriteLocks.ORDER`, so its chunks take no new lock.
  `ScimLargeMembershipIntegrationTests` holds every path with 2,200 members or
  Groups.

### Collation and the seed lock

On the pinned image (SQL Server 2022 CU27 for Linux), a server collated
`Latin1_General_100_BIN2_UTF8` or `Latin1_General_100_BIN2` cannot load the
internal extended procedures `xp_userlock` (behind `sp_getapplock`) and
`sp_executesql` when T-SQL calls them: error 17750, "Could not load the DLL (server
internal)". In `sqlcmd`, `sp_getapplock` also returned `0`, "granted", for a lock
it never took. The driver's own prepared-statement calls (an RPC) are unaffected,
and so is `EXEC (@string)`.

- **Only the RDS server is collated `Latin1_General_100_BIN2_UTF8`** (its
  `CharacterSetName`, as #71 names it). Flyway 12.4's SQL Server support guards
  every migration with `EXEC sp_getapplock … @LockOwner = 'Session'`, with no
  setting to turn it off, so on a `BIN2_UTF8` Linux server no database migrates
  (`FlywaySqlException: Unable to acquire SQL Server application lock`). The test
  containers and the local compose server therefore keep their default server
  collation. The database is `Latin1_General_100_BIN2_UTF8` everywhere. Flyway's
  lock is not replaced: a plugin overriding it worked in a prototype, but would
  depend on Flyway's internal SQL Server classes.
- **The seed lock is a row lock, not an application lock.** `V1__initial_schema.sql`
  creates `scim_seed_locks` and inserts its one row, `ScimSeed`.
  `ScimSeedLockAdapter` takes it with `SELECT … WITH (UPDLOCK, ROWLOCK)` and no
  `READPAST`: a second start waits for the first start's seeding to commit, then
  reads what it seeded. A missing row fails the start, as a missing job-lock row
  does (ADR 0005). `backend_app` holds `SELECT` on the table and nothing else,
  which an `UPDLOCK` read needs (measured). The statement's query timeout is 60 s
  (`TimedJdbc`); a wait that runs out is rethrown as `CannotAcquireLockException`,
  "the seeding lock was not granted within 60 s". `Propagation.MANDATORY`.
- **The rest of the service's T-SQL avoids both procedures.** `ForcedDeadlock`
  (test) closes its cycle on two rows of its own table, the partner reading the
  writer's row with `READCOMMITTEDLOCK`. The bootstrap runs its per-database step
  through `EXEC (@ddl)`, with the owner's name escaped into the batch.
- **Risk: untested on Windows.** RDS for SQL Server runs the Windows build, which
  could not be measured here, and the suite does not reproduce this one deployed
  setting (`infra/docs/database-engine.md`, "Collation and time zone"). If the
  Windows build has the same defect, the first deploy fails loudly:
  `backend-migrate.service` fails with that `FlywaySqlException` and 17750, and
  `backend.service`, which `Requires=` it, never starts. The way out is a server
  collation that takes the lock — RDS's default, or
  `Latin1_General_100_CI_AS_SC_UTF8` / `Latin1_General_100_CS_AS_SC_UTF8`, which
  take it on the Linux image. Changing the collation replaces the instance.

### The deployed bootstrap

- **It runs on the instance, as the master user, and its T-SQL is the local
  init's.** The bootstrap runs in UserData, so the instance role reads the master
  secret (as-8). It is read by one root-owned script and never reaches the
  service's process, which `smoke.sh` checks; the role itself cannot tell
  processes apart, which `infra/docs/database-credentials.md` records as residual
  risk. The T-SQL is held twice — in the template and in
  `backend/scripts/local-db-init.sql` — because a stack cannot read the
  repository; `infra/check-bootstrap-parity.sh` keeps the two equal, and
  `deploy.sh` runs it before every deploy. A re-run is a no-op: a login's password
  is reset only when the calling script found that the login cannot sign in with
  its secret's.
- **`rds.force_ssl` refuses a client that cannot do TLS, and encrypts one that asks
  for none** (dp-3). A client whose TDS PRELOGIN says it cannot encrypt
  (`ENCRYPT_NOT_SUP`) is answered `ENCRYPT_REQ` and its plaintext login is dropped
  unread, which `smoke.sh` proves with `infra/tds-plaintext-probe.py` (it sends no
  real credential). A client that can do TLS but asks for none (`sqlcmd -No`,
  `encrypt=false`) is encrypted instead of refused: the `sqlserver-se-16.0`
  parameter family has no counterpart of SQL Server 2022's strict-encryption
  switch. TLS 1.0 and 1.1 are off (`rds.tls10`, `rds.tls11`). The bootstrap's
  `sqlcmd -N` validates the RDS certificate against the RDS CA bundle given to it
  alone (`SSL_CERT_FILE`).
- **The trust-store password reaches the driver from the environment.**
  `DATABASE_URL` names the driver-scoped store; its password, from Secrets
  Manager, is exported as
  `SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_TRUSTSTOREPASSWORD`, which Boot
  binds lower-cased into the driver properties of every pool and the migrate step,
  and the driver matches property names case-insensitively
  (`EveryPoolConnectsAlikeTests`, `MigrationConnectionTests`).

### The test server

The backend's gate scripts (`scripts/verify.sh`, and the layers of
`scripts/test-gates.sh` that need Docker) start one SQL Server for the run
(`scripts/test-sql-server.sh`), create the owner, app and retention logins on it,
and name it to every fork in the `TEST_SQL_SERVER_*` variables; the container is
removed when the script exits. `ContainerTestConfiguration` then gives each context
an empty database on that server, created with the deployment's creation options
and named with a token of its JVM's so two JVMs never create the same one, and its
own Redis database on a Redis container per JVM. Without the variables — an IDE, a
bare `./mvnw` — each JVM starts its own server.

A server per JVM outgrew a small development VM's memory at two forks, and a third
fork made the run slower. With the server shared, the fork count stays 2. It rests
on the tests that read server-wide views (`sys.dm_tran_locks`,
`sys.dm_exec_requests`, `sys.dm_exec_sessions`) scoping them to `DB_ID()` or
`@@SPID`, and on no test changing a server principal or a server setting. The
image is read from `compose.yaml`'s `sqlserver` service, so the pin is still held
in the two places `/README.md` names.

### Compliance resolutions (IM8)

- **as-8 Secrets management.** Every database credential lives in AWS Secrets
  Manager: the RDS master user through an RDS-managed secret, and the owner, app
  and retention logins through generated secrets. No password appears in
  CloudFormation parameters, UserData, env files or `deploy.sh` prompts, and the
  instance role may read only the secrets its process needs. Rotation is out of
  scope (see Consequences).
- **ac-1 Least privilege.** Migrations run out of process: a one-shot migrate step
  (the same JAR, with no web server, Flyway enabled and the owner credentials),
  run by the deploy before the service starts, is the only holder of the owner
  secret. The deployed service runs with Flyway disabled and receives only the app
  and retention credentials; `ddl-auto: validate` still fails its startup on
  schema drift. Local development and tests keep in-process Flyway.
- **as-13 Exposure of internal system details.** Database errors — permission
  denied (229), the trigger's refusals, a failed application lock, snapshot update
  conflicts (3960) — surface as the existing generic `500`, with no driver text in
  the body. A deadlock victim (1205) on a SCIM or administrative write is retried
  once, by a single interceptor at the outermost transactional boundary, never
  nested; a second 1205 answers `503` with `Retry-After: 1`. Read-only and
  scheduled paths are not retried: the next request or run covers them.
- **dp-3 Data in transit.** `rds.force_ssl = 1` in a dedicated parameter group
  refuses unencrypted connections. The JDBC URL requires TLS 1.2 at minimum and
  validates the server certificate; the bootstrap's `sqlcmd` encrypts and does not
  trust the server certificate blindly. `trustServerCertificate=true` is allowed
  only locally and in tests, where the certificate is self-signed, and a
  configuration test fails the build if it appears in any other resource or
  profile.
- **as-2 Parameterised interfaces.** The bootstrap and the Flyway placeholders put
  login names into DDL, so the names must match `^[a-z][a-z0-9_]{2,30}$`, enforced
  by CloudFormation and by a check before Flyway migrates, and DDL wraps them with
  `QUOTENAME`. Passwords reach `sqlcmd` only as scripting variables from the
  environment, are escaped in T-SQL, and are generated without quote characters.
- **as-4 Authentication rate-limiting.** Lockout counting must stay atomic under
  read-committed snapshot. A concurrent-lockout integration test fires parallel
  failed Logins against a User one short of the limit and asserts exactly one
  Lockout and no lost update of the failure run.
- **lm-16 Key signals.** Both connection pools (`app` and `retention`) are exposed
  through Micrometer. RDS gets Enhanced Monitoring and Performance Insights, and
  CloudWatch alarms on sustained deadlocks and on blocked processes.
- **lm-19 Log sanitisation.** The driver logger (`com.microsoft.sqlserver.jdbc`) is
  pinned to `WARN`, and Hibernate's JDBC error logger stays off, because SQL Server
  quotes the refused value in a unique-violation message.
  SQL exception text is redacted at the logging boundary (see the standards
  resolutions).
- **ck-2 Key rotation, ck-4 Key storage.** Storage stays encrypted, under a
  customer-managed KMS key with rotation enabled and a key policy limited to RDS,
  Secrets Manager and the deploy role's administration. It encrypts storage,
  automated backups, snapshots, Performance Insights and the database secrets.

These close as-8, ck-4 and dp-3 for the database (ADR 0011's entries record it);
as-8 and ck-4 stay open for the Epic signing key until its KMS signer exists.

### Standards resolutions (App-Standards: User Standalone, User SSO, Structured Logging)

- **`error.code` is the HTTP status only.** The Log Schema limits `error.code` to
  `100–599`, so a database failure logs the HTTP status it answered (`500` or
  `503`), never the SQL Server error number.
- **Error categories.** Each database failure maps to an `error.category`,
  `error_follow_up_action` and level:

  | Failure                                                           | `error.category` | Follow-up           | Level   |
  | ----------------------------------------------------------------- | ---------------- | ------------------- | ------- |
  | 1205 deadlock, first                                              | `database`       | — (retried)         | `WARN`  |
  | 1205 deadlock, second; 3960 snapshot conflict                     | `database`       | investigate         | `ERROR` |
  | Untranslated 2627/2601, 2628 truncation, 245/8114 conversion      | `database`       | investigate         | `ERROR` |
  | Application-lock failure or timeout; the audit trigger's refusals | `database`       | investigate         | `ERROR` |
  | TLS trust failure, 18456 login failure, 229 permission denied     | `cert/auth`      | check configuration | `ERROR` |

  The retry's `WARN` carries `retry.attempt=1` and `reason=deadlock`.

- **SQL exception text is redacted.** The ECS error customizer replaces the message
  of every `SQLException` in the cause chain, in `error.message` and in each
  `Caused by:` line of `error.stack_trace`, with `SQL Server error <n>`, keeping
  class names and frames. No connection string, host or database name is logged:
  Flyway's database-information loggers are pinned to `WARN`, and a startup-log
  test asserts it for the service and the migrate step.
- **Bounded timeouts.** The seed lock waits at most 60 s and fails startup loudly
  on timeout; JDBC login times out after 15 s; both pools' connection timeout is
  10 s; each retention batch and each job-lock statement has a 60 s query timeout.
  Request paths keep their existing defaults.
- **The audit retention job logs one summary line per run** — outcome, job status,
  deleted rows and batches, duration — and a run skipped on a held lock logs one
  `skipped` line.
- **Storage is UTC only.** RDS keeps its UTC default time zone, stated explicitly;
  Hibernate writes every timestamp at offset `+00:00`; no column default uses
  `SYSDATETIMEOFFSET()` or `GETDATE()`; reads normalize to `Instant`; the API
  renders `Z`. Hand-written JDBC binds every timestamp as an `OffsetDateTime` at
  `ZoneOffset.UTC`, never as a `java.sql.Timestamp`. This rule is not a fallback:
  V2 passed, and the rule was added from evidence V2 recorded. The driver sends a
  `Timestamp` as the JVM-local wall clock in a `datetime2`, and SQL Server reads
  that wall clock as UTC when it compares it with a `DATETIMEOFFSET`. A bound
  `Timestamp` is therefore off by the JVM's zone offset unless the JVM runs in UTC.
  The audit retention cutoff and the audit listing's time bounds already bind an
  `OffsetDateTime` at `ZoneOffset.UTC`, Hibernate's `jdbc.time_zone` is pinned to
  UTC, and the `be-jdbc-timestamp-bind` Semgrep rule keeps new `Timestamp` binds
  out. Reads may use
  `getObject(…, OffsetDateTime.class)` or `getTimestamp(…)`, which both return
  the right instant. The driver refuses `getObject(…, Instant.class)`.
- **The RDS CA trust store is driver-scoped.** Only the SQL Server connection uses
  it, through the JDBC URL's `trustStore`, with its password from Secrets Manager.
  The JVM's default trust store stays untouched for the identity provider, JWKS
  and Epic.
- **Existing Hikari settings keep binding.** Declaring a second DataSource makes
  Boot's DataSource auto-configuration back off, so the primary is declared
  explicitly and still binds `spring.datasource.hikari.*`; the retention pool binds
  its own prefix. Boot's `db` health indicator covers both pools.
- **The bootstrap does not echo.** Deployment scripts run without command tracing,
  secrets reach `sqlcmd` only through scripting variables, `sqlcmd` runs without
  echoing input, and a post-deploy step fails the deploy if any secret value
  appears in the instance's boot log. The local init follows the same rules.
- **Admin and fixture seeding are unchanged.** The Bootstrap Admin and the Admin
  group are still seeded by the service at startup under the seed lock, never by
  `V1` or the init scripts, which create infrastructure principals only.
  Development fixtures stay gated by `APP_DEV_FIXTURES_ENABLED`, off in the
  deployed stack.
- **A column inventory** accompanies the T-SQL `V1`: every `VARCHAR` column is
  classified as ASCII-only (keeps `n`) or Unicode-capable (`4n`, or `VARCHAR(MAX)`
  where unbounded), Epic identity columns included, and is reviewed with the
  schema. The SCIM page query's `UNION ALL` casts a branch's text `NULL` to a
  `VARCHAR` that must be at least as wide as the widest text column it is unioned
  with — `VARCHAR(1024)` under the ×4 rule today — so widening a sort column means
  widening that cast in `ScimQuerySql` too.
- **No first-login insert to make race-safe.** Only SCIM provisions Users: Epic
  Login never creates one (ADR 0012, no just-in-time provisioning).
- **Database patching.** RDS applies minor versions automatically, and the pinned
  SQL Server image tag used locally and in tests is reviewed monthly. A repo-wide
  dependency-CVE gate is a separate concern, not part of this decision.

### Observable changes

The engine shows through the API in these behaviours, which are documented in the
API contract and `docs/domain/`:

- **Edge whitespace is refused** on the keys listed above, a validation rule in
  the API contract.
- **SCIM free-text `eq` ignores trailing spaces** (#70). Filters `eq`, `ne`, `gt`, `ge`, `lt` and `le` on
  attributes that keep their whitespace — `name.*`, `locale`, `timezone`,
  `preferredLanguage` and the like — ignore trailing spaces in the stored and the
  compared values, because SQL Server pads the comparison. `co`, `sw` and `ew`
  keep trailing spaces significant: they are `LIKE` against a bound Unicode
  pattern, which SQL Server does not pad. The padding is accepted, not worked
  around, and
  `ScimQueryProtocolIntegrationTests.free_text_equality_ignores_trailing_spaces_and_substrings_do_not`
  pins every operator.
- **SCIM whitespace-only free text counts as absent.** `pr` and sorting treat an
  empty string as no value (`<> ''`, `NULLIF(…, '')`), and under SQL Server's
  padding a value of only spaces compares equal to `''`. A whitespace-only value
  of an attribute that keeps its whitespace therefore fails `pr` and sorts as a
  missing value.
- **Ties break in `uniqueidentifier` order.** Paging that ties on the sort key breaks
  the tie by resource `id` in SQL Server's `uniqueidentifier` order, not
  lexicographic order. Paging is a total order.
- **Case-insensitive SQL comparisons** use SQL Server's `LOWER` under the
  `BIN2_UTF8` collation, and spike V4 found that it does not always match Java's
  `toLowerCase(Locale.ROOT)`. The SCIM filters, sorts and sort keys that lower-case
  both sides (`userName`, `name.*`, `displayName`, `preferredLanguage`, `locale`,
  `timezone`, `emails.value`, `emails.type`, `groups.display`, `members.display`,
  `meta.*`) therefore treat these characters as case-sensitive:
  - ASCII, Latin-1, Latin Extended-A and -B, the modern Greek and Cyrillic
    alphabets, full-width Latin and the circled letters lower-case as Java does.
  - 582 upper-case code points stay unchanged under `LOWER` although Java
    lower-cases them. Among them are the Kelvin, Ohm and Angstrom signs
    (U+212A, U+2126, U+212B), capital sharp s (U+1E9E), Georgian Mtavruli, Cherokee,
    Latin Extended-C and -D, Cyrillic Extended-B, and the supplementary-plane
    scripts such as Deseret, Osage and Adlam. A filter `eq "𐐨"` therefore does not
    match a stored `𐐀`.
  - Three Greek upsilon-with-hook symbols (U+03D2–U+03D4) are lower-cased by
    `LOWER`, but not by Java.
  - `LOWER` maps U+0130 (İ) to `i`, while Java maps it to `i` followed by U+0307.
  - `LOWER` has no final-sigma rule: `ΟΔΟΣ` lower-cases to `οδοσ`, where Java gives
    `οδος`.

  The full list, by code point, is under "Verified behaviour". Case-insensitive
  uniqueness is unaffected, because it compares the application-normalized
  columns.

SCIM `userName` keeps following RFC 7643 and the identity provider's format, not
the Standalone standard's alphanumeric-only rule. That deviation is recorded in
ADR 0011.

## Consequences

- The security properties are enforced by the database: the audit
  trail is append-only for the application, only the retention login may delete
  aged-out events, nobody may update one, and the runtime connection has least
  privilege. Each has an integration test that connects as the specific login and
  asserts the server's refusal.
- The deployed service never holds the owner credential. A schema change is a
  deploy step that runs before the service, and a failed migration stops the deploy
  before the service starts.
- The baseline gate, `backend/scripts/verify.sh`, runs on Testcontainers SQL
  Server; there is no in-memory database or dialect emulation.
- Hosting is costly: RDS SQL Server Standard Edition carries a licence fee and
  needs a larger instance class.
- Credential rotation is a known gap. The credentials sit in Secrets Manager, so
  rotation (a rotation function plus a pool credential refresh) can be added later
  without redesign, but this decision does not provide it.
- Apple Silicon is not a supported development platform.

## Alternatives considered

**Keep PostgreSQL.** The technically preferred option and App-Standards'
recommendation. Not open: the organisational mandate decides the engine.

**Support both engines.** Rejected: it doubles the hand-written SQL, the migrations
and the test matrix for good, for a deployment that will only ever run one.

**SQL Server 2019.** Rejected: already security-fixes-only, and it lacks
`IS [NOT] DISTINCT FROM`.

**Azure SQL Database or Managed Instance.** Out of scope: the deployment is on AWS,
and RDS keeps it managed and AWS-native.

## Verified behaviour

Spikes V1–V6 ran on 2026-10-12, before the switchover. Each names what would have
changed if it had failed, and ran on a throwaway harness outside the repository,
so none of that code merges.

| #   | Question                                                                                                              | If it fails                                                                        | Verdict                                             |
| --- | --------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------- |
| V1  | Does `ddl-auto: validate` accept `DATETIMEOFFSET(6)`, `UNIQUEIDENTIFIER`, `BIT` and `VARBINARY` for the mapped types? | Set Hibernate's preferred JDBC types, or per-column type codes.                    | Pass, no settings needed                            |
| V2  | Do `OffsetDateTime` and `UUID` round-trip through the driver?                                                         | Keep binding and reading UUIDs as strings in hand-written JDBC (the default).      | Pass; bind timestamps as UTC `OffsetDateTime`       |
| V3  | Does `READPAST` skip a held job lock row under read-committed snapshot?                                               | Use `sp_getapplock` with a zero timeout per job name; amend this ADR and ADR 0005. | Pass                                                |
| V4  | Does `LOWER` under the collation match `toLowerCase(Locale.ROOT)` for the normalized columns' characters?             | List the differences as observable changes, or compare normalized columns only.    | Fail; differences listed under "Observable changes" |
| V5  | Does Hibernate report the constraint or index name for 2627/2601?                                                     | Parse the known constraint names from the driver exception.                        | Pass; `IntegrityViolations` unchanged               |
| V6  | Are the test container's start time and memory acceptable with the suite's fork count?                                | Lower the fork count, or raise the documented WSL memory.                          | Pass at `forkCount` 2                               |

The pinned versions were:

- **Image.** `mcr.microsoft.com/mssql/server:2022-CU27-ubuntu-22.04`, image
  `sha256:4402d880dd4c…` (amd64). `@@VERSION` reported Microsoft SQL Server 2022
  RTM-CU27 (KB5104824), 16.0.4295.3, Developer Edition.
- **Libraries.** What the backend's Spring Boot 4.1.1 BOM resolves: mssql-jdbc
  `13.4.0.jre11` ("Microsoft JDBC Driver 13.4 for SQL Server 13.4.0.0") and
  Hibernate ORM `7.4.5.Final`, which selected `org.hibernate.dialect.SQLServerDialect`
  at version 16.0. Java 25, with the JVM zone left at the host's `Asia/Singapore`, so
  a zone-dependent result could not pass by accident.
- **Database.** Created the way this ADR decides:
  `CREATE DATABASE … COLLATE Latin1_General_100_BIN2_UTF8`, then
  `COMPATIBILITY_LEVEL = 160`, `READ_COMMITTED_SNAPSHOT ON` and
  `ALLOW_SNAPSHOT_ISOLATION ON`. `sys.databases` read back
  `Latin1_General_100_BIN2_UTF8 160 1 ON`.
- **Schema.** A T-SQL `V1`, with `UNIQUEIDENTIFIER`,
  `DATETIMEOFFSET(6)`, `BIT`, `VARBINARY(64)`, `VARCHAR(4n)` and `VARCHAR(MAX)`, and
  the token permissions in their child table.

### V1: schema validation accepts the mapped types

All ten entities booted with `ddl-auto=validate` and no other Hibernate setting:
`DATETIMEOFFSET(6)` for `Instant`, `UNIQUEIDENTIFIER` for `UUID`, `BIT` for
`boolean`, `VARBINARY` for `byte[]`. A `DATETIME2(6)` column failed validation as
the negative control. Hibernate stored an entity's instant at offset 0 with the
JVM at `+08:00`, and the UUID's server text matched Java's (no byte-order swap).

### V2: the driver round-trips `OffsetDateTime` and `UUID`

`setObject` / `getObject(…, type)` round-trip both, so hand-written JDBC binds
`UUID` directly. `getObject()` with no type returns a UUID as an upper-case
string, and `getObject(…, Instant.class)` is refused by the driver. A bound
`java.sql.Timestamp` arrives as the JVM-local wall clock in a `datetime2`, which
SQL Server reads as UTC against a `DATETIMEOFFSET`: with the JVM in
`Asia/Singapore` a cutoff query returned the wrong row, while a UTC
`OffsetDateTime` was right in every zone. "Storage is UTC only" records the rule.

### V3: `READPAST` skips a held job-lock row under read-committed snapshot

Two `READ COMMITTED` connections on the RCSI database: a held row returned nothing
at once without blocking, another job's row was not skipped, the row returned once
its holder committed or rolled back, and the plain count still told a held row
from a missing one (ADR 0005).

### V4: `LOWER` differs from `toLowerCase(Locale.ROOT)`

Every Unicode scalar value except U+0000 (1,112,063 code points) was stored as a
one-code-point string in a `VARCHAR(16)` column with the database collation. Each
one came back byte-identical. Each was then compared with
`String.toLowerCase(Locale.ROOT)` on Java 25:

```text
code points Java lower-cases = 1460; SQL LOWER changes = 881
LOWER(varchar) differs from LOWER(nvarchar) for 0
LOWER(varchar) != Java for 586
kinds = {SQL leaves unchanged, Java lowers = 582; SQL lowers, Java leaves unchanged = 3; both lower, differently = 1}
```

A bound `nvarchar` parameter, as `ScimQuerySql` binds its values, gives the same
results as the column. The differences by code point (a run marked "even" or "odd"
covers every second code point):

| Block                     | Code points                                                                                                                                                                                                                                                                  | Count | `LOWER`                          |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----- | -------------------------------- |
| Latin Extended-A          | U+0130                                                                                                                                                                                                                                                                       | 1     | `i`, where Java gives `i` U+0307 |
| Greek                     | U+0370, U+0372, U+0376, U+037F, U+03CF, U+03F4                                                                                                                                                                                                                               | 6     | unchanged                        |
| Greek                     | U+03D2–U+03D4                                                                                                                                                                                                                                                                | 3     | lowers; Java leaves unchanged    |
| Cyrillic Supplement       | U+0514–U+052E (even)                                                                                                                                                                                                                                                         | 14    | unchanged                        |
| Georgian                  | U+10C7, U+10CD                                                                                                                                                                                                                                                               | 2     | unchanged                        |
| Cherokee                  | U+13A0–U+13F5                                                                                                                                                                                                                                                                | 86    | unchanged                        |
| Cyrillic Extended-C       | U+1C89                                                                                                                                                                                                                                                                       | 1     | unchanged                        |
| Georgian Extended         | U+1C90–U+1CBA, U+1CBD–U+1CBF                                                                                                                                                                                                                                                 | 46    | unchanged                        |
| Latin Extended Additional | U+1E9E, U+1EFA–U+1EFE (even)                                                                                                                                                                                                                                                 | 4     | unchanged                        |
| Letterlike Symbols        | U+2126, U+212A, U+212B                                                                                                                                                                                                                                                       | 3     | unchanged                        |
| Glagolitic                | U+2C2F                                                                                                                                                                                                                                                                       | 1     | unchanged                        |
| Latin Extended-C          | U+2C6D–U+2C70, U+2C72, U+2C7E, U+2C7F                                                                                                                                                                                                                                        | 7     | unchanged                        |
| Coptic                    | U+2CEB, U+2CED, U+2CF2                                                                                                                                                                                                                                                       | 3     | unchanged                        |
| Cyrillic Extended-B       | U+A640–U+A66C (even), U+A680–U+A69A (even)                                                                                                                                                                                                                                   | 37    | unchanged                        |
| Latin Extended-D          | U+A722–U+A72E (even), U+A732–U+A76E (even), U+A779–U+A77D (odd), U+A77E–U+A786 (even), U+A78B, U+A78D, U+A790, U+A792, U+A796–U+A7AA (even), U+A7AB–U+A7AE, U+A7B0–U+A7B4, U+A7B6–U+A7C4 (even), U+A7C5–U+A7C7, U+A7C9, U+A7CB, U+A7CC, U+A7D0, U+A7D6–U+A7DC (even), U+A7F5 | 90    | unchanged                        |
| Deseret                   | U+10400–U+10427                                                                                                                                                                                                                                                              | 40    | unchanged                        |
| Osage                     | U+104B0–U+104D3                                                                                                                                                                                                                                                              | 36    | unchanged                        |
| Vithkuqi                  | U+10570–U+1057A, U+1057C–U+1058A, U+1058C–U+10592, U+10594, U+10595                                                                                                                                                                                                          | 35    | unchanged                        |
| Old Hungarian             | U+10C80–U+10CB2                                                                                                                                                                                                                                                              | 51    | unchanged                        |
| Garay                     | U+10D50–U+10D65                                                                                                                                                                                                                                                              | 22    | unchanged                        |
| Warang Citi               | U+118A0–U+118BF                                                                                                                                                                                                                                                              | 32    | unchanged                        |
| Medefaidrin               | U+16E40–U+16E5F                                                                                                                                                                                                                                                              | 32    | unchanged                        |
| Adlam                     | U+1E900–U+1E921                                                                                                                                                                                                                                                              | 34    | unchanged                        |

Whole strings, bound as parameters:

```text
ΟΔΟΣ ΣΑΣ -> οδοσ σασ   (Java: οδος σας; no final-sigma rule)
İSTANBUL -> istanbul   (Java: i̇stanbul)
ẞ -> ẞ   𐐀𐐁 -> 𐐀𐐁   𞤀 -> 𞤀   Ა -> Ა   Ꭰ -> Ꭰ                     (Java lowers each)
ı, ß, ǅ ǈ ǋ ǲ, ﬀ ﬁ, Ⅻ, Ⓐ, Ａ, ÀÉÎÕÜ, ĀĂĄ, ПРИВЕТ, ΑΒΓ, STRASSE   (match Java)
```

A second run on fresh containers reproduced the 586.

The fallback chosen is to document the differences. They are listed under
"Observable changes". Uniqueness already compares application-normalized columns,
and no other comparison is moved onto them.

### V5: Hibernate reports the constraint and index name

Unique-key (2627) and unique-index (2601) violations, by native insert and by
entity flush, surfaced as `ConstraintViolationException` carrying the constraint
name, so `IntegrityViolations` works unchanged provided `V1` keeps the constraint
names. Both messages quote the refused value, as lm-19 assumes.

### V6: test container cost at `forkCount` 2

A container idles at about 0.9 GB and stays near 1.2 GB under load; two start side
by side within a 2 GB limit each, so the fork count stayed 2. The gate scripts
have since shared one server across the forks ("The test server").
