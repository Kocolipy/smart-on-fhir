# 15. SQL Server 2022 replaces PostgreSQL

Date: 2026-10-12

## Status

Accepted as the decision. Its implementation lands across later tickets: the
engine-neutral preparation on PostgreSQL (#62–#66), the verification spikes (#67)
and the switchover (#68–#72). Until the switchover merges, the backend still runs
on PostgreSQL, and this ADR describes the target, not the running system.

Some decisions below depend on verification spikes that have not yet run (V1–V6,
listed under "Open verification points"). Where a spike's fallback would change a
decision, the decision says so; a failed spike amends this ADR rather than being
absorbed silently in code.

This ADR will supersede in part ADR 0004 (how the audit trail's append-only rule is
enforced in the database; the fail-closed/fail-open axis is unchanged) and ADR 0005
(the `SKIP LOCKED` mechanism; the per-job lock row model is unchanged). The
superseding notes on those two ADRs land with the tickets that change those
mechanisms.

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
  Server's `LOWER`.
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
  `WITH (UPDLOCK, ROWLOCK, READPAST)` in place of `FOR UPDATE SKIP LOCKED`. This
  depends on spike V3; if `READPAST` does not skip a held row under read-committed
  snapshot, the lock becomes `sp_getapplock` with a zero timeout per job name, and
  this ADR and ADR 0005 are amended accordingly. The seed lock becomes
  `sp_getapplock` on a fixed resource name, transaction-owned.
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
  renders `Z`.
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
  schema.
- **First-login provisioning is race-safe.** A provisioning attempt that loses a
  unique-constraint race rolls back and re-reads the existing User, so two
  concurrent first Logins resolve to one User.
- **Database patching.** RDS applies minor versions automatically, and the pinned
  SQL Server image tag used locally and in tests is reviewed monthly. A repo-wide
  dependency-CVE gate is a separate concern, not part of this decision.

### Observable changes

The move keeps externally observable behaviour except for these, which are
documented in the API contract and `docs/domain-rules.md` when the switchover
lands:

- **Edge whitespace is refused** on the keys listed above. This is a validation
  change and lands in the API contract.
- **SCIM free-text `eq` ignores trailing spaces.** Filters (`eq`, `ne`, `co`, `sw`,
  `ew`) on attributes that keep their whitespace — `name.*`, `locale`, `timezone`,
  `preferredLanguage` and the like — ignore trailing spaces in the stored and the
  compared values, because SQL Server pads the comparison.
- **The UUID tie-break order changes.** Paging that ties on the sort key breaks the
  tie by resource `id` in SQL Server's `uniqueidentifier` order, not lexicographic
  order. Paging is still a total order; only which tied row comes first changes.
- **`error.type` changes** for data-access errors, from `PSQLException` to
  `SQLServerException`, and SQLSTATE-based values disappear. Before the switchover
  merges, operations confirms that no alert or dashboard keys on them, or updates
  them.
- **Case-insensitive SQL comparisons** use SQL Server's `LOWER` under the
  `BIN2_UTF8` collation. Whether it matches Java's `toLowerCase(Locale.ROOT)` for
  non-ASCII text is spike V4; any difference found is added here before the
  switchover merges.

SCIM `userName` keeps following RFC 7643 and the identity provider's format, not
the Standalone standard's alphanumeric-only rule. That is a pre-existing deviation,
unchanged by this move, and is recorded in ADR 0012.

### Delivery

- **Engine-neutral preparation lands on PostgreSQL first**, each change small,
  reviewable and releasable: edge-whitespace refusal, explicit membership deletes,
  the token permissions child table, separate logins and DataSources, out-of-process
  migrations, the credentials/KMS/TLS/monitoring infrastructure, logging hardening,
  and race-safe first-login provisioning. `V1` stays frozen meanwhile, so each schema
  change is a new PostgreSQL migration.
- **One integration branch then switches the engine**: driver, Flyway and
  Testcontainers modules; the T-SQL `V1`; the adapters; the tests; local
  development; infrastructure; and the documentation. Main stays releasable
  throughout.

### Open verification points

These spikes run first on the switchover; each names what changes if it fails.

| #   | Question                                                                                                              | If it fails                                                                        |
| --- | --------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| V1  | Does `ddl-auto: validate` accept `DATETIMEOFFSET(6)`, `UNIQUEIDENTIFIER`, `BIT` and `VARBINARY` for the mapped types? | Set Hibernate's preferred JDBC types, or per-column type codes.                    |
| V2  | Do `OffsetDateTime` and `UUID` round-trip through the driver?                                                         | Keep binding and reading UUIDs as strings in hand-written JDBC (the default).      |
| V3  | Does `READPAST` skip a held job lock row under read-committed snapshot?                                               | Use `sp_getapplock` with a zero timeout per job name; amend this ADR and ADR 0005. |
| V4  | Does `LOWER` under the collation match `toLowerCase(Locale.ROOT)` for the normalized columns' characters?             | List the differences as observable changes, or compare normalized columns only.    |
| V5  | Does Hibernate report the constraint or index name for 2627/2601?                                                     | Parse the known constraint names from the driver exception.                        |
| V6  | Are the test container's start time and memory acceptable with the suite's fork count?                                | Lower the fork count, or raise the documented WSL memory.                          |

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
