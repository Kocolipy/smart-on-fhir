# 15. SQL Server 2022 replaces PostgreSQL

Date: 2026-10-12

## Status

Accepted, and implemented. The engine-neutral preparation on PostgreSQL (#62–#66),
the verification spikes (#67) and the switchover on the integration branch
`mssql-cutover` (#68–#72) have landed: the backend runs on SQL Server 2022 locally
and in tests, the deployed stack is RDS for SQL Server (#71), and PostgreSQL
support is removed. This ADR describes the running system. "Verified behaviour"
closes with the switchover's own measurements.

Some decisions below depended on verification spikes (V1–V6). The spikes ran on
2026-10-12 against a SQL Server 2022 container. The table under "Open verification
points" is the one summary of their verdicts, and "Verified behaviour" holds the
evidence. No decision's fallback was taken; V2 added one binding rule, under
"Storage is UTC only". A failed spike amends this ADR rather than being absorbed
silently in code.

This ADR supersedes in part ADR 0004 (how the audit trail's append-only rule is
enforced in the database; the fail-closed/fail-open axis is unchanged) and ADR 0005
(the `SKIP LOCKED` mechanism; the per-job lock row model is unchanged). Both carry
their superseding notes: ADR 0005's landed with the locking ticket (#69), and ADR
0004's with the switchover's last ticket (#72).

It also records a deviation from the App-Standards recommendation of PostgreSQL
for production (Standalone User Access Control Q6, User SSO Q5).

## Context

The backend runs on PostgreSQL 18 locally and in tests, and on RDS PostgreSQL 15
when deployed. The organisation has mandated SQL Server as its database engine.
App-Standards recommends PostgreSQL for production, so moving off it is a
conscious deviation, made for **organisational mandate** and not for a technical
shortcoming of PostgreSQL.

The application is pre-production and holds no data that must be kept. Most of the
JPA/JPQL layer carries over through Hibernate's SQL Server dialect. The work sits in
the places that rely on PostgreSQL semantics:

- the role-based least-privilege model (`SET ROLE` on the pool, `SET LOCAL ROLE` for
  retention)
- the PL/pgSQL append-only trigger on `audit_events`
- the `TEXT[]` column holding token permissions
- the two `ON DELETE CASCADE` paths into `scim_group_members`
- the advisory seed lock and `FOR UPDATE SKIP LOCKED` on the job lock rows
- the audit listing's consistent snapshot under `REPEATABLE READ`
- the hand-written SCIM filter SQL (`ScimQuerySql`)
- string semantics: collation, padded comparison, and how lengths are counted

SQL Server differs on each of these: it has no `SET ROLE`, no arrays, no advisory
locks; it rejects multiple cascade paths to one table; its `REPEATABLE READ` is a
locking level, not a snapshot; it pads `=` comparisons and unique constraints with
trailing spaces under every collation; and `VARCHAR(n)` counts bytes, not code
points.

The move was reviewed on 2026-10-12 against the IM8 application control catalog and
against App-Standards (User Standalone, User SSO, Structured Logging). Every finding
of those reviews is resolved below, so the decision and its compliance conditions
are one record.

## Decision

### Scope

- **Full switch.** PostgreSQL support is removed, not kept beside SQL Server.
  Supporting both would permanently double the hand-written SQL, the migrations and
  the test matrix.
- **SQL Server 2022, database compatibility level 160.** Mainstream support runs to
  January 2028 and extended support to January 2033; 2019 already receives security
  fixes only. Level 160 adds `IS [NOT] DISTINCT FROM`, which the SCIM filter SQL
  uses.
- **No data migration.** The schema is rewritten in place as a single T-SQL `V1`.
  A fresh database is the only supported starting state; the PostgreSQL migrations
  added during preparation are folded into it.
- **RDS for SQL Server, Standard Edition, licence-included** in production:
  managed and AWS-native as today, without Express's 10 GB cap. **Developer
  Edition** locally and in tests, which is free for development and test use. The
  edition is a stakeholder sign-off, because Standard Edition costs far more than
  the current `db.t3.micro` PostgreSQL instance and does not run on the smallest
  instance classes.
- **x86_64 only** for local development (WSL2, Linux, CI). The SQL Server image has
  no arm64 build; Apple Silicon runs it only through slow, unsupported emulation,
  and is a documented limitation.

### Runtime model

- **Three logins, and no `SET ROLE`.** The **owner login** (`db_owner`) runs
  migrations and nothing else. The **app login** is the request pool's, a member of
  `backend_app` only. The **retention login** has its own small DataSource and
  transaction manager, is used only by the audit retention adapter, and is a member
  of `backend_audit_retention` only. `SET ROLE` and `SET LOCAL ROLE` are removed:
  the server enforces each login's privileges, and there is no session
  impersonation that could leak between pooled uses of one connection.
- **The audit append pool needs its own role switch** (added 2026-10-13). Since ADR
  0004's 2026-10-13 addendum, fail-open audit appends commit on a second, small
  pool of their own (`AuditAppendPoolConfig`). On PostgreSQL it takes the request
  pool's URL, login and `connection-init-sql` off the request pool, so
  `SET ROLE backend_app` reaches it by copy. SQL Server has no `SET ROLE`, so the copy carries
  no role, and the switchover must give this pool its role explicitly: it connects
  as the **app login** — the request pool's, a member of `backend_app` only — from
  the same secret, never as the owner login. The configuration test that pins the
  request pool's login covers this pool too, and lm-16's metrics name it beside
  the `app` and `retention` pools.
- **A connector token's row lock moves with the SCIM resource locks** (added
  2026-10-13). A rotation and a revocation lock the token's row before reading it
  (`ScimConnectorTokenJpaRepository.lockById`, `SELECT … FOR UPDATE`, taken only by
  `ScimWriteLockAdapter`), so of two concurrent rotations exactly one mints a
  replacement. It locks one row by primary key, so it needs no order; on SQL Server
  it becomes `WITH (UPDLOCK, ROWLOCK)`, and must still be the transaction's first
  read of the token for the waiting rotation to see the first one's replacement
  under read-committed snapshot.
- **The SCIM resource lock order is the application's, not the engine's** (added
  2026-10-14). A SCIM write locks every resource row it may advance before reading
  any of them, in one canonical order, so two writers whose sets overlap serialize
  instead of deadlocking. That order used to be
  `SELECT … WHERE id IN (…) ORDER BY id FOR UPDATE`, which holds only because
  PostgreSQL locks rows as the sort returns them; SQL Server under `UPDLOCK` locks
  rows as the scan reads them, before the sort, so the same statement would
  deadlock concurrent SCIM writes.
  `ScimWriteLockAdapter` now sorts the set itself (`ScimWriteLocks.ORDER`,
  `UUID#compareTo`) and locks one row by primary key per statement
  (`ScimResourceJpaRepository.lockResource`), which no engine can reorder; on SQL
  Server each becomes `WITH (UPDLOCK, ROWLOCK)` and the statement is otherwise
  unchanged. The order deliberately matches neither engine's `uuid` sort, so a
  statement that slid back to relying on one fails
  `ScimWriteLockAdapterIntegrationTests` on PostgreSQL today. The cost is a round
  trip per locked row — a rename of a Group with two hundred members takes two
  hundred and one — accepted because a batched statement would be correct only on an
  engine that locks in sort order, and ordered chunks reintroduce the engine's order
  inside each chunk. This bullet supersedes the migration spec's §5.3 "Unchanged
  code" entry for the SCIM resource lock (its "Conditional write row lock" row):
  that query is changed code, not unchanged. The same row's
  `UserCounterJpaRepository.findByUserIdForUpdate` is unaffected — one row by key
  under `PESSIMISTIC_WRITE`, which Hibernate renders as
  `WITH (UPDLOCK, HOLDLOCK, ROWLOCK)`.
- **Database creation options are set by whatever creates the database** (the
  deployed bootstrap, the local init, the test container), never by Flyway:
  `ALTER DATABASE … SET READ_COMMITTED_SNAPSHOT` cannot run inside a migration
  transaction. Flyway learns the login names through placeholders and never their
  passwords; it creates the database users `FOR LOGIN` and grants the roles.
- **`VARCHAR` with database collation `Latin1_General_100_BIN2_UTF8`.** UTF-8
  storage with code-point (byte) order is the closest match to PostgreSQL UTF-8
  with `COLLATE "C"`, which the SCIM sort relies on. Equality and the unique
  constraints stay case-sensitive; case-insensitive uniqueness keeps comparing the
  application-normalized (NFKC + lower-case) columns, so it never depends on SQL
  Server's `LOWER`. (Added 2026-10-14, #68.) The collation is the **database's**,
  set by `CREATE DATABASE … COLLATE`. The deployed RDS server is collated
  `Latin1_General_100_BIN2_UTF8` as well. The local and test servers keep their
  default collation: on the pinned image a server collated `BIN2_UTF8`
  (`MSSQL_COLLATION`) cannot load the internal extended procedures behind
  `sp_getapplock` and a T-SQL `sp_executesql` (error 17750, "Could not load the
  DLL (server internal)"). Flyway guards every migration with `sp_getapplock`, so
  on such a server nothing migrates (Addendum (2026-10-16)).
- **`READ_COMMITTED_SNAPSHOT ON` and `ALLOW_SNAPSHOT_ISOLATION ON`, with the audit
  listing at `SNAPSHOT`.** Readers do not block writers, as with PostgreSQL's MVCC
  today. The listing's count and page must see one consistent view; SQL Server's
  `REPEATABLE READ` does not give that, `SNAPSHOT` does.
- **Memberships are deleted explicitly, with no multi-path cascades.** SQL Server
  rejects two cascade paths into one table, so both member-side foreign keys of
  `scim_group_members` become `NO ACTION`, and the User and Group delete paths
  delete the resource's memberships in the same transaction before deleting the
  resource. Doing it in the application keeps the behaviour visible and testable;
  resource versions advance exactly as before. Every other cascade is single-path
  and stays.
- **Limits stay in code points; Unicode-capable columns widen ×4.** The API
  contract is unchanged. A UTF-8 code point takes at most four bytes, so every
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
  is refused as an invalid token. Neither changes behaviour relative to
  PostgreSQL.
- **Token permissions move to a child table**,
  `scim_connector_token_permissions(token_id, permission)`, keyed on both columns,
  with a CHECK limiting `permission` to the four directory Permissions. SQL Server
  has no arrays, and the child table keeps a constraint the database enforces
  today.
- **The append-only audit trigger is kept, in T-SQL.** An `INSTEAD OF UPDATE,
DELETE` trigger on `audit_events` throws unless the caller is a member of
  `backend_audit_retention`, and refuses every `UPDATE` from every principal. The
  retention role keeps **`SELECT, DELETE` only**; its `UPDATE` grant is revoked, so
  the grant and the trigger agree. No principal may `UPDATE` an audit row: audit
  rows are immutable, and only aged-out rows can be deleted, only by the retention
  login. The app login is refused by its grants first; the owner login and any
  mis-granted login are refused by the trigger, which is why the trigger stays as
  defence in depth.
- **The NUL (U+0000) refusal is kept, re-justified as input hygiene.** SQL Server
  can store NUL, so PostgreSQL's limitation no longer applies, but NUL is hostile in
  logs and terminals, and keeping the refusal changes nothing in the API.
- **`OPTION (RECOMPILE)` on the audit listing's count and page statements.** Their
  catch-all `(@x IS NULL OR col = @x)` filters would otherwise share one cached plan
  across every filter combination. The listing is a low-volume administrative
  read, so the compile cost does not matter. Both stay whole constant statements.
- **The scheduled job lock keeps its per-job rows** (ADR 0005), taken with
  `WITH (UPDLOCK, ROWLOCK, READPAST)` in place of `FOR UPDATE SKIP LOCKED`. Spike
  V3 verified that `READPAST` skips a held row under read-committed snapshot
  without waiting, so the `sp_getapplock` fallback is not needed. The seed lock
  becomes `sp_getapplock` on a fixed resource name, transaction-owned — superseded
  by a row lock on `scim_seed_locks` (Addendum (2026-10-16)).
- **`audit_events` is clustered on `(occurred_at, id)`**, with a non-clustered
  primary key on `id`. A random-UUID clustered key fragments on every insert into
  the highest-volume table, while retention deletes by `occurred_at` and the
  listing orders by it, so both become range operations. Retention deletes in
  batches below SQL Server's lock-escalation threshold, one transaction per batch,
  so a long run does not block audit appends; retention is idempotent, and the next
  run completes a partial one.

### Compliance resolutions (IM8)

- **as-8 Secrets management.** Every database credential lives in AWS Secrets
  Manager: the RDS master user through an RDS-managed secret, and the owner, app
  and retention logins through generated secrets. No password appears in
  CloudFormation parameters, UserData, env files or `deploy.sh` prompts, and the
  instance role may read only the secrets its process needs. This replaces an
  earlier draft that passed the passwords as NoEcho CloudFormation parameters,
  which still reached an env file through UserData. Rotation is out of scope (see
  Consequences).
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
  quotes the refused value in a unique-violation message just as PostgreSQL does.
  SQL exception text is redacted at the logging boundary (see the standards
  resolutions).
- **ck-2 Key rotation, ck-4 Key storage.** Storage stays encrypted, under a
  customer-managed KMS key with rotation enabled and a key policy limited to RDS,
  Secrets Manager and the deploy role's administration. It encrypts storage,
  automated backups, snapshots, Performance Insights and the database secrets.

ADR 0012 lists as-8, ck-4 and dp-3 as open. The resolutions above close them for
the database once they land; ADR 0012's entries are updated with the tickets that
land them, and as-8 and ck-4 stay open for the Epic signing key until its KMS signer
exists.

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
  out, so the switchover changes neither. Reads may use
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
  Login never creates one (ADR 0013, no just-in-time provisioning).
- **Database patching.** RDS applies minor versions automatically, and the pinned
  SQL Server image tag used locally and in tests is reviewed monthly. A repo-wide
  dependency-CVE gate is a separate concern, not part of this decision.

### Observable changes

The move keeps externally observable behaviour except for these, which are
documented in the API contract and `docs/domain-rules.md` when the switchover
lands:

- **Edge whitespace is refused** on the keys listed above. This is a validation
  change and lands in the API contract.
- **SCIM free-text `eq` ignores trailing spaces** (amended 2026-10-15, see
  "Addendum (2026-10-15)"). Filters `eq`, `ne`, `gt`, `ge`, `lt` and `le` on
  attributes that keep their whitespace — `name.*`, `locale`, `timezone`,
  `preferredLanguage` and the like — ignore trailing spaces in the stored and the
  compared values, because SQL Server pads the comparison. `co`, `sw` and `ew`
  keep trailing spaces significant: they are `LIKE` against a bound Unicode
  pattern, which SQL Server does not pad. This bullet first listed `co`, `sw` and
  `ew` among the operators that ignore trailing spaces and left out the ordering
  operators; the switchover measured otherwise.
- **SCIM whitespace-only free text counts as absent.** `pr` and sorting treat an
  empty string as no value (`<> ''`, `NULLIF(…, '')`), and under SQL Server's
  padding a value of only spaces compares equal to `''`. A whitespace-only value
  of an attribute that keeps its whitespace therefore fails `pr` and sorts as a
  missing value, where PostgreSQL treats it as present.
- **The UUID tie-break order changes.** Paging that ties on the sort key breaks the
  tie by resource `id` in SQL Server's `uniqueidentifier` order, not lexicographic
  order. Paging is still a total order; only which tied row comes first changes.
- **`error.type` changes** for data-access errors, from `PSQLException` to
  `SQLServerException`, and SQLSTATE-based values disappear. Before the switchover
  merges, operations confirms that no alert or dashboard keys on them, or updates
  them.
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

  The full list, by code point, is under "Verified behaviour". The İ and final
  sigma results are the same as PostgreSQL's today. Against the suite's
  PostgreSQL image, 515 of the code points are new differences, and 26 (the
  circled letters) now match Java where PostgreSQL did not. Case-insensitive
  uniqueness is unaffected, because it compares the application-normalized
  columns.

SCIM `userName` keeps following RFC 7643 and the identity provider's format, not
the Standalone standard's alphanumeric-only rule. That is a pre-existing deviation,
unchanged by this move, and is recorded in ADR 0012.

### Delivery

- **Engine-neutral preparation lands on PostgreSQL first**, each change small,
  reviewable and releasable: edge-whitespace refusal, explicit membership deletes,
  the token permissions child table, separate logins and DataSources, out-of-process
  migrations, the credentials/KMS/TLS/monitoring infrastructure, and logging
  hardening. `V1` stays frozen meanwhile, so each schema
  change is a new PostgreSQL migration.
- **One integration branch then switches the engine**: driver, Flyway and
  Testcontainers modules; the T-SQL `V1`; the adapters; the tests; local
  development; infrastructure; and the documentation. Main stays releasable
  throughout.

### Open verification points

These spikes ran before the switchover, on 2026-10-12. Each names what would have
changed if it had failed. The verdict column points to the evidence under
"Verified behaviour".

| #   | Question                                                                                                              | If it fails                                                                        | Verdict                                                  |
| --- | --------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | -------------------------------------------------------- |
| V1  | Does `ddl-auto: validate` accept `DATETIMEOFFSET(6)`, `UNIQUEIDENTIFIER`, `BIT` and `VARBINARY` for the mapped types? | Set Hibernate's preferred JDBC types, or per-column type codes.                    | Pass, no settings needed                                 |
| V2  | Do `OffsetDateTime` and `UUID` round-trip through the driver?                                                         | Keep binding and reading UUIDs as strings in hand-written JDBC (the default).      | Pass; bind timestamps as UTC `OffsetDateTime`            |
| V3  | Does `READPAST` skip a held job lock row under read-committed snapshot?                                               | Use `sp_getapplock` with a zero timeout per job name; amend this ADR and ADR 0005. | Pass                                                     |
| V4  | Does `LOWER` under the collation match `toLowerCase(Locale.ROOT)` for the normalized columns' characters?             | List the differences as observable changes, or compare normalized columns only.    | Fail; differences listed under "Observable changes"      |
| V5  | Does Hibernate report the constraint or index name for 2627/2601?                                                     | Parse the known constraint names from the driver exception.                        | Pass; `IntegrityViolations` unchanged                    |
| V6  | Are the test container's start time and memory acceptable with the suite's fork count?                                | Lower the fork count, or raise the documented WSL memory.                          | Pass at `forkCount` 2; about 2 GB more memory than today |

## Consequences

- The security properties carry over and stay enforced by the database: the audit
  trail is append-only for the application, only the retention login may delete
  aged-out events, nobody may update one, and the runtime connection has least
  privilege. Each has an integration test that connects as the specific login and
  asserts the server's refusal.
- The deployed service never holds the owner credential. A schema change is a
  deploy step that runs before the service, and a failed migration stops the deploy
  before the service starts.
- The baseline gate stays `backend/scripts/verify.sh`, on Testcontainers SQL Server
  instead of PostgreSQL; there is still no in-memory database or dialect
  emulation. The suite's context count must not rise, and its runtime is recorded
  before and after.
- Hosting costs rise: RDS SQL Server Standard Edition costs far more than the
  current instance, and needs a larger instance class.
- Credential rotation is a known gap. The credentials sit in Secrets Manager, so
  rotation (a rotation function plus a pool credential refresh) can be added later
  without redesign, but this decision does not provide it.
- Apple Silicon is not a supported development platform.
- `docs/domain-rules.md`, the API contract and the ADRs whose mechanisms change
  (0004, 0005, 0012) are updated with the tickets that land each change, not here.

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

Spikes V1–V6 ran on 2026-10-12. Each ran on a throwaway harness outside the
repository, so none of that code merges. The pinned versions were:

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
- **Schema.** A T-SQL rendering of today's `V1`, with `UNIQUEIDENTIFIER`,
  `DATETIMEOFFSET(6)`, `BIT`, `VARBINARY(64)`, `VARCHAR(4n)` and `VARCHAR(MAX)`, and
  the token permissions in their child table.

### V1: schema validation accepts the mapped types

All ten entities of the backend, copied unchanged, booted a Spring context with
`spring.jpa.hibernate.ddl-auto=validate` and no other Hibernate setting. The one
exception was `ScimConnectorTokenEntity.permissions`, the `text[]` column, which was
marked `@Transient` because it moves to the child table. Validation accepted
`DATETIMEOFFSET(6)` for `Instant`, `UNIQUEIDENTIFIER` for `UUID`, `BIT` for
`boolean` and `VARBINARY` for `byte[]`.

As a negative control, the same context against a copy whose
`scim_connectors.created_at` was `DATETIME2(6)` failed with `Schema validation:
wrong column type encountered in column [created_at] in table [scim_connectors];
found [datetime2 (Types#TIMESTAMP)], but expecting [datetimeoffset(7)
(Types#TIMESTAMP_UTC)]`. Validation compares the type and not the precision, so
`(6)` passes.

An entity persisted through Hibernate was stored at offset 0, although the JVM zone
was `+08:00`. It read back equal, and the UUID's server text matched Java's (no
byte-order swap):

```text
stored    = {id_text=0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0, created_text=2026-10-12T09:30:01.123456Z, offset_minutes=0, display_name=ünïcødé 𐐷}
read back = 0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0 2026-10-12T09:30:01.123456Z ünïcødé 𐐷
byte[] {1, 2, 3, 0xFF} stored as 0x010203FF
```

### V2: the driver round-trips `OffsetDateTime` and `UUID`

These ran over plain JDBC with `setObject` and `getObject(…, type)`:

```text
setObject(UUID) stored 0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0; getObject(UUID.class) = 0f1e2d3c-…-a5b4c3d2e1f0, equals = true
setObject(OffsetDateTime …Z)      -> getObject(OffsetDateTime.class) = 2026-10-12T09:30:01.123456Z
setObject(OffsetDateTime …+08:00) -> stored offset 480 minutes; reads back as …17:30:01.123456+08:00, the same instant
setObject(…01.123456789Z)         -> stored …01.123457Z (rounded to the column's microseconds, as PostgreSQL also rounds)
WHERE u = setObject(UUID) finds a row bound as CAST(? AS UNIQUEIDENTIFIER): count = 1
setNull(Types.NULL), and a JdbcTemplate null UUID argument -> NULL; getObject(UUID.class) on NULL = null
getObject() without a type returns the UUID as an upper-case String
getObject(…, Instant.class) -> SQLServerException: The conversion to class java.time.Instant is unsupported.
```

Hand-written JDBC therefore binds and reads `UUID` directly, and the string
fallback is not needed. A neighbouring check on `java.sql.Timestamp`, the type
the audit adapters bind today, found a zone-dependent bind. The row held
`09:30:00Z`, and the query was `WHERE t < ?` with a cutoff one minute **earlier**,
so it should return 0:

```text
JVM zone Asia/Singapore: setTimestamp(Timestamp.from(cutoff)) -> 1  (wrong)   setObject(OffsetDateTime UTC) -> 0
JVM zone UTC:            setTimestamp(Timestamp.from(cutoff)) -> 0           setObject(OffsetDateTime UTC) -> 0
Timestamp.from(2026-10-12T09:30:00Z) arrives as datetime2; as datetimeoffset = 2026-10-12T17:30:00Z
```

The driver property `datetimeParameterType=datetimeoffset` did not change the
result. `getTimestamp(…).toInstant()` on a `DATETIMEOFFSET` returned the right
instant in both zones. "Storage is UTC only" above records the rule that follows.

### V3: `READPAST` skips a held job-lock row under read-committed snapshot

There were two connections, both at `READ COMMITTED` (`sys.dm_exec_sessions`
reported level 2), on the RCSI database. Each ran `SELECT job_name FROM
scheduled_job_locks WITH (UPDLOCK, ROWLOCK, READPAST) WHERE job_name = ?` inside an
open transaction:

```text
A locks dormancy:                                [dormancy] in 47 ms
B tries dormancy while A holds it:               [] in 3 ms
B plain count of dormancy while A holds it:      [1] in 4 ms
B tries audit-retention while A holds dormancy:  [audit-retention] in 3 ms
lock held: KEY U GRANT, PAGE IU GRANT, OBJECT IX GRANT (each session)
A committed
B tries dormancy after A ended:                  [dormancy] in 4 ms
A tries dormancy while B now holds it:           [] in 2 ms
B rolled back, A tries again:                    [dormancy] in 2 ms
missing job row:                                 [] in 3 ms; count [0] in 5 ms
```

A held row returns nothing immediately, without blocking. The row returns once the
holder ends, whether it commits or rolls back. Another job's row is not skipped.
`ScheduledJobLockAdapter`'s plain-count follow-up does not wait for the lock, so it
still tells a held row from a missing one. ADR 0005's decision needs no amendment
beyond the superseding note recording the new statement (`SKIP LOCKED` → `READPAST`).

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

For reference, `lower()` on today's test image (`postgres:18.6-alpine`, libc
provider, `en_US.utf8`) differs from Java on 97 code points, and the two lists
share 71 of them. The 26 circled letters U+24B6–U+24CF differ on PostgreSQL
only, and 515 code points differ on SQL Server only. PostgreSQL also gives
`οδοσ` and `i` for the two context cases. A second run on fresh containers
reproduced the 586.

The fallback chosen is to document the differences. They are listed under
"Observable changes". Uniqueness already compares application-normalized columns,
and no other comparison is moved onto them.

### V5: Hibernate reports the constraint and index name

Each case went through `EntityManager`: a native insert, and an entity persist plus
flush. Spring's JPA dialect translated it:

```text
2627 uq_scim_users_normalized_user_name (native):
  DataIntegrityViolationException
    <- org.hibernate.exception.ConstraintViolationException constraintName=uq_scim_users_normalized_user_name kind=UNIQUE
    <- SQLServerException errorCode=2627 sqlState=23000 "Violation of UNIQUE KEY constraint 'uq_scim_users_normalized_user_name'. … The duplicate key value is (alice)."
  IntegrityViolations.violates(…) = true (also with the name upper-cased)
2627 uq_scim_groups_normalized_display_name (native):          constraintName=uq_scim_groups_normalized_display_name, violates = true
2627 uq_scim_connector_tokens_lookup_id (entity persist+flush): constraintName=uq_scim_connector_tokens_lookup_id, violates = true
2601 uq_scim_user_emails_one_primary (filtered unique index):
    <- ConstraintViolationException constraintName=uq_scim_user_emails_one_primary kind=UNIQUE
    <- SQLServerException errorCode=2601 "Cannot insert duplicate key row in object 'dbo.scim_user_emails' with unique index 'uq_scim_user_emails_one_primary'. …"
  violates = true
```

`IntegrityViolations` works unchanged, so its case-insensitive name comparison
needs no change, provided the T-SQL `V1` keeps the constraint names. Both messages
quote the refused value, as lm-19 already assumes.

### V6: test container cost at `forkCount` 2

These are wall-clock measurements on a 24-vCPU, 47 GB WSL2 host, not the 20-core,
5.9 GB VM in the `pom.xml` comment. The start time runs from `docker run` to the
first successful `sqlcmd` query:

| Measurement                                                                           | SQL Server 2022                                                     | PostgreSQL 18.6 (today)         |
| ------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | ------------------------------- |
| Container start, one at a time                                                        | 6.7–7.0 s (3 runs); 12.2–12.5 s in an earlier set of 3              | 1.6–2.5 s (6 runs)              |
| Container start, two at once (`forkCount` 2)                                          | 11.5 s and 11.5 s; 12.2 s and 12.4 s earlier                        | —                               |
| Memory per container, idle after start                                                | 0.90 GB                                                             | 0.17 GB                         |
| Peak per container: 14 contexts created and migrated, then a 1.1M-row insert and scan | 1.17–1.19 GB (no limit); 0.82 GB (`--memory 2g`)                    | 0.17 GB (14 contexts)           |
| Per-context database (create, set options, migrate)                                   | 0.97–1.14 s (create 0.54–0.66, options 0.23–0.27, schema 0.19–0.27) | 0.24 s (create 0.13, `V1` 0.11) |

- **Memory.** SQL Server is far below its limit in this workload. Two
  containers need about 2 GB more than today's two PostgreSQL containers. A 2 GB
  container limit is SQL Server's documented minimum. Under it, the pair started
  in 13.8 s and 13.9 s, about 1.5 s slower than without a limit, and the
  per-context work was no slower. A 1.5 GB limit still reached "ready for client
  connections".
- **Per-context work.** One run set the snapshot options on `model`, so that new
  databases inherit them. It was not faster (1.06 s create, 0.37 s schema).
- **Projection.** Each fork starts its own containers once and creates about 14
  databases (27 contexts across two forks). The forks run in parallel, so the
  gate grows by about (11.5 − 2.3) s + 14 × (1.0 − 0.24) s ≈ **20 s**. Query cost
  inside the tests is not included, because it can only be measured once the
  adapters run on SQL Server; the switchover records the suite's runtime before
  and after, as Consequences requires.
- **Today's gate.** On this host, `./scripts/verify.sh` took 268 s wall-clock.
  Maven reported `Total time: 04:11 min` for `clean verify`, with 4464 tests and
  28 context starts. The rest was the Semgrep scan. That projects to roughly
  290 s on SQL Server, about 7% more. No other session's containers ran during
  that gate (at most 6 containers, which were the gate's own two sets of Postgres,
  Redis and Ryuk). The load average, including the gate itself, averaged 3.8 and
  peaked at 5.3 on 24 vCPUs. The SQL Server timings above ran with at most one
  other session's Postgres and Redis set alive. An earlier gate run on 2026-10-12,
  with six other sessions' containers alive, took 532 s.

The fork count stays 2. The memory the switchover's local-development
documentation states must cover the extra ~2 GB. On a VM as small as the
`pom.xml` comment's 5.9 GB, that is tight, and is the case for raising the
documented WSL memory rather than lowering the fork count.

### The switchover: the suite before and after (2026-10-16)

Consequences requires the suite's runtime before and after, and a context count
that does not rise. Both are `./scripts/verify.sh` from `backend/`, on the 24-vCPU,
47 GB WSL2 host of V6; the context count is the gate log's
`grep -c 'Started .* in .* seconds'`.

| Run                                          | Wall-clock | Maven `Total time` | Tests (run / failed / errors) | Contexts | Semgrep    |
| -------------------------------------------- | ---------- | ------------------ | ----------------------------- | -------- | ---------- |
| PostgreSQL, 6e8a882 (#68's baseline)         | 308 s      | 04:48 min          | 5033 / 0 / 0                  | 32       | 0 findings |
| SQL Server, 95ee952 (#71 merged, before #72) | 437 s      | 07:14 min          | 5122 / 21 / 11                | 33       | 0 findings |
| SQL Server, #72, run 1                       | 489 s      | 07:49 min          | 5122 / 0 / 0                  | 33       | 1 finding  |
| SQL Server, #72, run 2                       | 427 s      | 06:44 min          | 5122 / 0 / 0                  | 32       | 0 findings |
| SQL Server, #72, run 3 (`make verify`)       | 436 s      | 06:39 min          | 5122 / 0 / 0                  | 34       | 0 findings |
| SQL Server, #72, run 4 (`V1` comments)       | 396 s      | 06:34 min          | 5122 / 1 / 0                  | 34       | not run    |
| SQL Server, #72, final run (`GATE_EXIT=0`)   | 458 s      | 07:18 min          | 5122 / 0 / 0                  | 33       | 0 findings |

- **Contexts.** 33 in the final gate against 32 on PostgreSQL, and 32 to 34
  across the #72 runs. No configuration was added. The extra starts are the
  shared configuration rebooting after a `@DirtiesContext` eviction: which class
  boots it depends on where the forks place the two `@DirtiesContext` classes,
  which is why the count read 31 to 33 between runs on PostgreSQL and in #68 too.
  Run 3's wall-clock includes the frontend gate.
- **Runtime.** The final gate took 458 s against 308 s, 150 s (49%) more; the
  four green-test runs took 396 to 489 s. That is well above V6's projection of
  about 290 s, which left query cost inside the tests out. The 95ee952 row stopped
  at its red tests before its scan (the scan ran on its own, 0 findings), and its
  failing waits ran to their patience. Run 1's finding was a concatenated SQL
  string in a test, fixed before run 2. Run 4's one failure was
  `RefusalLogIntegrationTests.a_non_admin_admin_read_is_one_refusal_record`, which
  captured six `ERROR` records from Spring Session's minute cleanup task of an
  already stopped context ("LettuceConnectionFactory has been STOPPED") firing at
  11:00:00 inside its capture window; the final run, on the same tree, passed it.
  During the #72 runs a 12-vCPU virtual machine outside the suite ran on the same
  host (load average 3.9 to 7.3), so part of the difference may be the host
  rather than the engine.
- **The 32 red tests at 95ee952.** They were the tests #68 and #69 assigned to
  #72, in ten integration classes. Eight compared ids read back with an untyped
  `getObject`, which the driver answers with the upper-case text of the id (V2),
  and four still sent PostgreSQL SQL (`?::uuid`, `resource_id::text`,
  `locked_at IS NOT NULL` as a value, `now()`, `||`); two did both. The tests now
  read a row's `UNIQUEIDENTIFIER` columns as `UUID` (`UuidColumnRows`) and send
  T-SQL; no production code changed for them.
- **End to end.** `make integration-test` passed 60 Playwright tests, and
  `make epic-integration-test` passed the Epic project's 7, against a local stack
  on the pinned SQL Server image, the first on a freshly initialised database.
- **Deployed stack: unverified, pending the user's own deploy.** The criterion is
  that a stack deployed with `infra/deploy.sh` comes up, migrates, and serves SCIM
  create, list and delete. The switchover did not run it: the user deploys and
  verifies the AWS stack themselves. Until then nothing here shows the deployed
  stack's behaviour, including the untested Windows build's `sp_getapplock` under
  the `Latin1_General_100_BIN2_UTF8` server collation (Addendum (2026-10-16),
  "Risk: untested on Windows"). `V1`'s comments were reworded in #72, which changes
  its Flyway checksum, so a stack already deployed from `mssql-cutover` must be
  recreated rather than updated.

## Addendum (2026-10-15): substring filters keep trailing spaces significant

The "Observable changes" bullet on SCIM free text said that `eq`, `ne`, `co`, `sw`
and `ew` ignore trailing spaces. The switchover's SCIM query ticket (#70) measured
the filters on the pinned SQL Server image, and the list was wrong in both
directions. The bullet now carries the measured list.

- **Padded: `eq`, `ne`, `gt`, `ge`, `lt`, `le`.** These render as `=`, `<>`, `>`,
  `>=`, `<` and `<=`, and SQL Server pads the shorter operand with spaces before it
  compares. Against a stored `name.familyName` of `"Trail  "`, `eq "Trail"` and
  `eq "trail "` match, `ne "Trail"` does not, `ge "trail "` matches, and
  `gt "trail"` does not, because the padded values are equal.
- **Not padded: `co`, `sw`, `ew`.** These render as `LIKE` against a bound
  pattern. The driver sends the pattern as `nvarchar`, and a Unicode `LIKE` keeps
  trailing spaces significant on both sides. Against the same stored value,
  `ew "l"` does not match, while `ew "l  "` and `co "l "` do. A substring filter
  therefore matches as it did on PostgreSQL.

`ScimQueryProtocolIntegrationTests.free_text_equality_ignores_trailing_spaces_and_substrings_do_not`
pins every one of these results through the SCIM API. The decision is unchanged:
the padding is accepted as an observable change and not worked around, and the
API contract and `docs/domain-rules.md` list the corrected operators.
## Addendum (2026-10-15): the deployed bootstrap on RDS for SQL Server

The infrastructure ticket (#71) put the stack on RDS for SQL Server 2022 Standard
Edition. Five of its findings refine decisions above; none reverses one. The
first and the TLS one depart from the issue's wording, and wait on the user's
sign-off.

- **The owner login is a `db_owner` member, not the database's owner.** "Runtime
  model" names the owner login `db_owner`, and the local init and the tests had
  made it the database's owner (`ALTER AUTHORIZATION`). RDS's master user, which
  creates the database, cannot hand it to another login: that needs `IMPERSONATE`
  on the login, which the master user does not hold. The deployed bootstrap
  therefore creates a database user for the owner login and adds it to
  `db_owner`, and the local init and `ContainerTestConfiguration` now do the same,
  so the suite migrates `V1` under the deployed arrangement
  (`MigrationStepIntegrationTests.theOwnerLoginMigratesAsADbOwnerMemberNotAsTheDatabasesOwner`).
  The audit trigger refuses `db_owner` members, so it refuses the owner either
  way.
- **The bootstrap runs on the instance, as the master user, and its T-SQL is the
  local init's.** The deployed bootstrap runs in UserData, so the instance role
  reads the master secret (as-8). It is read by one root-owned script and never
  reaches the service's process, which `smoke.sh` checks; the role itself cannot
  tell processes apart, which `infra/README.md` ("Database credentials") records
  as residual risk. The T-SQL is held twice — in the template and in
  `backend/scripts/local-db-init.sql` — because a stack cannot read the
  repository; `infra/check-bootstrap-parity.sh` keeps the two equal, and
  `deploy.sh` runs it before every deploy. A re-run is a no-op: a login's
  password is reset, by the shared SQL, only when the calling script found that
  the login cannot sign in with its secret's.
- **The server keeps its default collation on RDS too.** Issue #71 names
  `Latin1_General_100_BIN2_UTF8` as the RDS *server* collation; the instance
  states `SQL_Latin1_General_CP1_CI_AS` instead, and the database keeps
  `Latin1_General_100_BIN2_UTF8` as "VARCHAR with database collation" decided.
  Measured on the pinned image: under a server collated
  `Latin1_General_100_BIN2_UTF8` or `Latin1_General_100_BIN2`, `sp_getapplock`
  raises error 17750 from `sys.xp_userlock` and returns `0`, "granted", for a
  lock it never took, in every database; under `Latin1_General_100_CI_AS_SC_UTF8`
  and `Latin1_General_100_CS_AS_SC_UTF8` it takes the lock. The seed lock would
  silently lock nothing. RDS runs the Windows build, where this was not
  measured, so the default is kept rather than staking the seed lock on it.
  (Superseded 2026-10-16: the seed lock no longer depends on `sp_getapplock`,
  and the RDS server is now collated `Latin1_General_100_BIN2_UTF8`; see the
  next addendum.)
- **`rds.force_ssl` refuses a client that cannot do TLS, and encrypts one that
  asks for none** (dp-3). A client whose TDS PRELOGIN says it cannot encrypt
  (`ENCRYPT_NOT_SUP`) is answered `ENCRYPT_REQ` and its plaintext login is
  dropped unread, which `smoke.sh` proves with `infra/tds-plaintext-probe.py`
  (it sends no real credential). A client that can do TLS but asks for none
  (`sqlcmd -No`, `encrypt=false`) is encrypted instead of refused, and its
  session reports `encrypt_option = TRUE`. No RDS for SQL Server parameter
  refuses that second client: the `sqlserver-se-16.0` family has no counterpart
  of SQL Server 2022's strict-encryption switch. TLS 1.0 and 1.1 are off in the
  parameter group (`rds.tls10`, `rds.tls11`). The bootstrap's `sqlcmd -N`
  validates the RDS certificate against the RDS CA bundle given to it alone
  (`SSL_CERT_FILE`).
- **The trust-store password reaches the driver from the environment.**
  `DATABASE_URL` names the driver-scoped store; its password, from Secrets Manager,
  is exported as `SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_TRUSTSTOREPASSWORD`,
  which Boot binds lower-cased into the driver properties of every pool and the
  migrate step, and the driver matches property names case-insensitively
  (`EveryPoolConnectsAlikeTests`, `MigrationConnectionTests`).

## Addendum (2026-10-16): the seed lock is a row lock

The seed lock moves off `sp_getapplock`, so that it holds under the server
collation issue #71 names, `Latin1_General_100_BIN2_UTF8`.

- **Why.** On the pinned image (SQL Server 2022 CU27 for Linux), a server
  collated `Latin1_General_100_BIN2_UTF8` or `Latin1_General_100_BIN2` cannot load
  the internal extended procedures `xp_userlock` (behind `sp_getapplock`) and
  `sp_executesql` when T-SQL calls them: error 17750, "Could not load the DLL
  (server internal)". Through the driver the seed lock's batch failed with that
  error; in `sqlcmd`, `sp_getapplock` also returned `0`, "granted", for a lock it
  never took. The driver's own prepared-statement calls (`sp_prepexec`, an RPC)
  are unaffected, and so is `EXEC (@string)`.
- **The row.** `V2__scim_seed_lock_row.sql` creates `scim_seed_locks` and inserts
  its one row, `ScimSeed`. `ScimSeedLockAdapter` takes that row with
  `SELECT … WITH (UPDLOCK, ROWLOCK)` and no `READPAST`: a second start waits for the
  first start's seeding to commit, then reads what it seeded. The migration
  inserts the row, so there is one to lock on a fresh database too — the reason
  the seed lock was an application lock no longer holds. A missing row fails the
  start ("the migration that creates it has not run"), as a missing job-lock row
  does (ADR 0005).
  Folded into `V1__initial_schema.sql` before the switchover reached `main`,
  so a fresh database has one migration; the table, row and grant are
  unchanged.
- **Grant.** `backend_app` holds `SELECT` on the table and nothing else. An
  `UPDLOCK` read needs no more (measured), so the runtime role can lock the row
  but cannot change or delete it.
- **Bounded wait.** The statement's query timeout is 60 s (`TimedJdbc`). A wait
  that runs out reaches the adapter as Spring's `QueryTimeoutException`
  (`SQLTimeoutException`, `HY008`), which it rethrows as
  `CannotAcquireLockException`, "the seeding lock was not granted within 60 s":
  `database` in the mapping, as before. `Propagation.MANDATORY` stays.
- **The rest of the service's T-SQL.** `ForcedDeadlock` (test) closes its cycle
  on two rows of its own table rather than on an application lock. The partner
  reads the writer's row with `READCOMMITTEDLOCK`, so its shared lock ends with
  the statement. The bootstrap (`local-db-init.sql` and the template's copy) runs
  its per-database step through `EXEC (@ddl)`, with the owner's name escaped
  into the batch, instead of `sys.sp_executesql`.
- **Flyway's own lock.** Flyway 12.4's SQL Server support guards every migration
  with `EXEC sp_getapplock … @LockOwner = 'Session'`, and there is no setting to
  turn it off. On a `BIN2_UTF8` server on the pinned image it fails with 17750
  (`FlywaySqlException: Unable to acquire SQL Server application lock`), so no
  database migrates there.
- **Decision: only the RDS server is collated `Latin1_General_100_BIN2_UTF8`.**
  The instance's `CharacterSetName` is `Latin1_General_100_BIN2_UTF8`, as issue
  #71 names it. The test containers and the local compose server keep their
  default server collation, because they run the Linux image, where Flyway
  could not migrate. The database is `Latin1_General_100_BIN2_UTF8` everywhere.
  Flyway's lock is not replaced: a plugin overriding it worked in a prototype,
  but it would depend on Flyway's internal SQL Server classes and change how
  migrations are serialized.
- **Risk: untested on Windows.** RDS for SQL Server runs the Windows build,
  which could not be measured here. If it has the same defect, the first
  deploy fails loudly, never silently. `backend-migrate.service` fails with
  that `FlywaySqlException` and 17750, and `backend.service`, which `Requires=`
  it, never starts, so the deploy's health check and smoke fail.
  - The way out is a server collation that takes the lock: RDS's default, or
    `Latin1_General_100_CI_AS_SC_UTF8` / `Latin1_General_100_CS_AS_SC_UTF8`, which
    take it on the Linux image. Changing the collation replaces the instance.
  - The suite does not reproduce this one deployed setting (`infra/README.md`,
    "Collation and time zone").

## Addendum (2026-10-16): an `IN` list a client sizes is bound in chunks

SQL Server refuses a request carrying more than 2,100 parameters (error 8003),
and Hibernate binds each element of a collection parameter as one. PostgreSQL
has no such ceiling, so the switchover left two statements whose `IN` list a SCIM
client sizes without a cap. A Group with more than about 2,099 members could not
be created with them, renamed or deleted (`500`), a User in that many Groups
could not be deleted, and the administrative listing failed once the directory
held that many Groups.

- **The rule.** A statement whose `IN` list has no cap of its own is issued once
  per chunk of at most 2,000 ids (`BoundIdChunks`), in the caller's transaction.
  That covers the version advance (`RepresentationChange#advanceIn`), which a
  rename, a delete or a membership change sizes by membership, and the Group
  adapter's membership read, which the listing sizes by the directory. A list a
  page bounds (`ScimPageRequest.MAX_COUNT`, 200) or the role mapping bounds (an
  operator's configuration) binds whole.
- **No new deadlock.** Every row a version advance names is already locked, one
  row per statement in `ScimWriteLocks.ORDER`, by `ScimWriteLockAdapter`, so its
  chunks take no new lock.
- **Held by** `ScimLargeMembershipIntegrationTests`: 2,200 members or Groups,
  every path above, on the test container.

The same day's review corrected V1's grant on `scheduled_job_locks` to `SELECT`
alone. A locking read (`UPDLOCK`) needs only `SELECT` permission, as the seed
lock's addendum above measured, and the application never updates a job row
(ADR 0005, amendment of 2026-10-16). Changing V1 changes its checksum, so a database
migrated from the earlier V1 fails Flyway's validation at the next migrate. A
fresh database is the only supported starting state: recreate such a database
(locally, remove the compose volume) rather than repairing the history.
