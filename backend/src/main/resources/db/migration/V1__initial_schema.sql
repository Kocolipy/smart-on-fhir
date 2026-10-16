-- The complete schema, in T-SQL for SQL Server 2022 (ADR 0015). The application is
-- pre-production and a fresh database is the only supported starting state, so the
-- schema is one migration rather than a history of them; the earlier migrations
-- of the engine-neutral preparation (#62–#66) are folded into it. A change from here
-- on is a new migration.
--
-- What this migration does NOT set: the database's collation
-- (Latin1_General_100_BIN2_UTF8), its compatibility level (160), and
-- READ_COMMITTED_SNAPSHOT / ALLOW_SNAPSHOT_ISOLATION. Those are the database's
-- creation options, set by whatever creates it — the test container setup, the local
-- compose init, the deployed bootstrap — because `ALTER DATABASE … SET
-- READ_COMMITTED_SNAPSHOT` cannot run inside the transaction Flyway migrates in.
--
-- Every id in this schema is assigned by the application before the INSERT, so
-- every layer above the database already agrees on a row's identity before it
-- exists, and the audit event that records a creation can name it. No id column
-- has a server-side default: a row with no id supplied is a defect in the
-- application, not something the database should paper over.
--
-- Types, chosen once for the whole schema:
--
--   UNIQUEIDENTIFIER   every id. Hibernate and the driver bind `UUID` directly.
--   DATETIMEOFFSET(6)  every instant, in whole microseconds. The
--                      application writes every one at offset +00:00 (Hibernate's
--                      `jdbc.time_zone` is UTC, hand-written JDBC binds an
--                      OffsetDateTime at UTC), and no column default reads the
--                      server's clock: SYSDATETIMEOFFSET() and GETDATE() appear
--                      nowhere, so the application's Clock is the only clock.
--   BIT                every flag.
--   VARBINARY          the one byte string, a token digest.
--   VARCHAR            every string, stored as UTF-8 under the database collation
--                      Latin1_General_100_BIN2_UTF8: code-point order, and
--                      case-sensitive equality.
--                      VARCHAR(n) counts BYTES, while the application's limits count
--                      code points, so every column that may hold any Unicode text
--                      is four times its limit (a code point is at most four bytes
--                      in UTF-8), and a column that holds only ASCII — a closed set
--                      of names from the code, a generated value — keeps its limit.
--                      An unbounded one is VARCHAR(MAX). The column inventory in the
--                      switchover's pull request classifies every one of them.
--
-- Every constraint keeps the name it had before the switchover: the persistence adapters
-- translate a unique violation by the constraint or index name the driver reports
-- (IntegrityViolations), so a renamed one would silently stop being translated.

-- =============================================================================
-- 1. The SCIM resource model
-- =============================================================================

-- `scim_resources` exists as a table of its own rather than as columns on the
-- per-type tables because the SCIM `id` namespace is shared: RFC 7643 requires an
-- id to be unique across every resource type, so a User and a Group may never
-- collide. Two tables each generating their own ids could only promise that by
-- trusting UUID randomness; one table holding the id, its type and its version
-- promises it with a primary key.
--
-- The version lives here too, beside the id, because every resource type
-- versions the same way and conditional writes lock this row. Keeping it out of
-- the per-type tables is what lets a single statement take the lock whatever
-- kind of resource is being written.
CREATE TABLE scim_resources (
    id               UNIQUEIDENTIFIER  NOT NULL,

    -- 'User' or 'Group', spelled as RFC 7643 spells the resource type, because
    -- this value is rendered into `meta.resourceType` unchanged. ASCII-only.
    resource_type    VARCHAR(16)       NOT NULL,

    -- Monotonically increasing from 1, rendered as the strong ETag and as
    -- `meta.version`. A BIGINT rather than a timestamp: a version has to change
    -- on every representation change even when two changes land in the same
    -- clock tick, which a timestamp cannot promise.
    version          BIGINT            NOT NULL,

    created_at       DATETIMEOFFSET(6) NOT NULL,

    last_modified_at DATETIMEOFFSET(6) NOT NULL,

    -- What makes a resource protected, for both kinds at once.
    --
    -- The deployment's recovery identity and its Admin Group cannot be renamed,
    -- rewritten or deleted, and the Bootstrap Admin's membership of that Group
    -- cannot be removed. Those refusals need to recognise the resources they are
    -- about, and recognising them by `userName` or `displayName` would mean the
    -- protection depended on an attribute — which, for any other resource, is
    -- mutable. A marker on the resource row does not: it is set once by seeding
    -- and there is no write path that changes it.
    --
    -- Nullable, and NULL is the ordinary case: almost every resource is
    -- unprotected. Non-null IS the protection, so "is this protected" is one
    -- column test that reads the same for a User and for a Group. ASCII-only: a
    -- closed set of names.
    reserved_name    VARCHAR(32),

    CONSTRAINT pk_scim_resources PRIMARY KEY (id),

    CONSTRAINT ck_scim_resources_type
        CHECK (resource_type IN ('User', 'Group')),

    -- A version below 1 would render an ETag no client could have been given.
    CONSTRAINT ck_scim_resources_version_positive
        CHECK (version >= 1),

    -- A closed set, checked here as well as in the domain enum, because the
    -- value decides whether writes are refused: a typo that stored 'admin_group'
    -- would silently leave the Admin Group unprotected and its authority
    -- underivable.
    CONSTRAINT ck_scim_resources_reserved_name
        CHECK (reserved_name IS NULL
               OR reserved_name IN ('bootstrap-admin', 'admin-group'))
);

-- Unique so seeding is idempotent against the database rather than against a
-- prior read: a restart that races another instance cannot produce two Admin
-- Groups, because the second INSERT violates this.
--
-- A filtered unique index rather than a UNIQUE constraint: SQL Server's unique
-- constraints treat NULLs as equal, so a constraint would admit only one
-- unprotected resource. The filter keeps NULL — the ordinary case — out of the
-- index, so any number of resources may be unprotected and each reserved name is held once.
CREATE UNIQUE INDEX uq_scim_resources_reserved_name
    ON scim_resources (reserved_name)
    WHERE reserved_name IS NOT NULL;

EXEC sys.sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'Names a resource the deployment reserves for recovery: the Bootstrap Admin User or the Admin Group. Non-null means every write and delete against the resource is refused. NULL for every ordinary resource.',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE', @level1name = N'scim_resources',
    @level2type = N'COLUMN', @level2name = N'reserved_name';

-- The SCIM User is the only login identity: it owns the profile and the
-- authentication state. Authority is not a column; it is derived from membership
-- of the Admin Group.
CREATE TABLE scim_users (
    -- The resource id IS the user's primary key: a User is a resource, not a row
    -- that points at one, so there is no second identifier to keep in step.
    resource_id                    UNIQUEIDENTIFIER  NOT NULL,

    -- As the connector sent it, case and all. What is rendered back. Also the
    -- Epic Practitioner ID an Epic Login links to (ADR 0013, D2), so an SSO
    -- identity is stored here and nowhere else. 256 code points.
    user_name                      VARCHAR(1024)     NOT NULL,

    -- The normalized form uniqueness is decided on, stored rather than computed
    -- in the index so the normalization rule lives in one place — the domain —
    -- and the database enforces the result instead of re-implementing the rule in
    -- SQL, where the two could drift. SQL Server's LOWER does not lower-case as
    -- Java does (ADR 0015, V4), which is one more reason the index compares this
    -- column and not a function of `user_name`. 256 code points.
    normalized_user_name           VARCHAR(1024)     NOT NULL,

    -- Nullable: a credentialless User is a supported state. It cannot log in
    -- until a password is set, which is a fact about authentication rather than
    -- about whether the identity may exist. An Argon2id encoding: ASCII-only.
    password_hash                  VARCHAR(256),

    -- No column default. `active` defaults to true on SCIM create, and that
    -- default belongs to the create use case where the RFC puts it; a second copy
    -- here would apply to writes the use case never saw.
    active                         BIT               NOT NULL,

    -- The profile, 256 code points each.
    display_name                   VARCHAR(1024),

    -- The `name` complex attribute, flattened. One row per User rather than a
    -- sub-table, because the sub-attributes are single-valued and the whole
    -- complex value is written and read as a unit.
    formatted_name                 VARCHAR(1024),
    family_name                    VARCHAR(1024),
    given_name                     VARCHAR(1024),
    middle_name                    VARCHAR(1024),
    honorific_prefix               VARCHAR(1024),
    honorific_suffix               VARCHAR(1024),

    -- 64 code points each. A connector may send any string here, not only a
    -- well-formed tag, so they are Unicode-capable.
    preferred_language             VARCHAR(256),
    locale                         VARCHAR(256),
    timezone                       VARCHAR(256),

    -- The authentication state below is application-owned and NOT a SCIM
    -- attribute: it is absent from `/Schemas`, is not filterable, and writing it
    -- does not advance the resource's version or `meta.lastModified`.

    -- The failure run. The default is a genuine one, unlike `active`'s: zero is
    -- the only value a newly provisioned User's failure run can start at, and a
    -- SCIM create names neither this column nor `locked_at`.
    failed_login_attempts          INT               NOT NULL
        CONSTRAINT df_scim_users_failed_login_attempts DEFAULT 0,

    -- When a lock was IMPOSED, and nothing about when it ends, because it does
    -- not end on its own. A lock is lifted by an administrator's Unlock, so the
    -- column's presence is the state and no clock is consulted to read it.
    locked_at                      DATETIMEOFFSET(6),

    -- Why the User is locked (ADR 0011): a failure run (ADR 0007) or dormancy,
    -- imposed by the dormancy job at the lockout window. It sits beside
    -- `locked_at`, set whenever it is set and cleared with it, so a helpdesk
    -- operator can tell a forgotten password from an abandoned account before
    -- unlocking. ASCII-only: a closed set.
    lock_cause                     VARCHAR(16),

    -- When the User last logged in successfully. Nullable, because a User that
    -- has never authenticated has no such instant — the dormancy job then
    -- measure from `scim_resources.created_at`, so a credentialless User is not
    -- treated as dormant the moment it is provisioned. An explicit
    -- inactive-to-active transition also writes it (the reactivation time),
    -- which is what resets the dormancy window for a reactivated User.
    last_authenticated_at          DATETIMEOFFSET(6),

    -- When the User's current credential was imposed on it by somebody else.
    -- The column's presence IS the flag, as `locked_at`'s presence is the
    -- lockout: a User with a value here must replace its password before it may
    -- do anything but submit that change or log out. Set by every connector
    -- password write, by an Admin's forced change and by an Unlock, and cleared
    -- by nothing but a successful self-service change.
    password_change_required_since DATETIMEOFFSET(6),

    CONSTRAINT pk_scim_users PRIMARY KEY (resource_id),

    -- Uniqueness among live Users, case-insensitively, as RFC 7643 requires of
    -- `userName`. A deleted User's row is gone, which is what makes a former
    -- userName reusable. SQL Server pads a unique key's trailing spaces, so
    -- `ada` and `ada ` would collide here; the application refuses a userName
    -- with edge whitespace before it reaches this (ADR 0015).
    CONSTRAINT uq_scim_users_normalized_user_name UNIQUE (normalized_user_name),

    -- The last line behind the application: a write path that set one of
    -- `locked_at` and `lock_cause` without the other cannot store it. `NULL IN
    -- (...)` is UNKNOWN and a CHECK accepts UNKNOWN, so the locked branch spells
    -- `lock_cause IS NOT NULL` explicitly: without it a lock with no cause would
    -- evaluate to `FALSE OR UNKNOWN` and be stored.
    CONSTRAINT ck_scim_users_lock_cause
        CHECK ((locked_at IS NULL AND lock_cause IS NULL)
               OR (locked_at IS NOT NULL AND lock_cause IS NOT NULL
                   AND lock_cause IN ('FAILURES', 'DORMANCY'))),

    CONSTRAINT fk_scim_users_resource
        FOREIGN KEY (resource_id) REFERENCES scim_resources (id) ON DELETE CASCADE
);

EXEC sys.sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'When the User was locked, by its failure run or by the dormancy job; NULL when it is not locked. A lock has no expiry — only an administrator''s Unlock clears this.',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE', @level1name = N'scim_users',
    @level2type = N'COLUMN', @level2name = N'locked_at';

EXEC sys.sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'Why the User is locked: FAILURES or DORMANCY. NULL exactly when locked_at is.',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE', @level1name = N'scim_users',
    @level2type = N'COLUMN', @level2name = N'lock_cause';

EXEC sys.sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'When the User last logged in successfully, or was last explicitly reactivated; NULL when neither has happened. The dormancy basis, with created_at as the fallback.',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE', @level1name = N'scim_users',
    @level2type = N'COLUMN', @level2name = N'last_authenticated_at';

EXEC sys.sp_addextendedproperty
    @name = N'MS_Description',
    @value = N'When a password change was last required of the User; NULL when none is. Cleared only by a successful self-service change.',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE', @level1name = N'scim_users',
    @level2type = N'COLUMN', @level2name = N'password_change_required_since';

CREATE TABLE scim_user_emails (
    resource_id UNIQUEIDENTIFIER NOT NULL,

    -- Position in the multi-valued attribute, so the order a connector sent is
    -- the order rendered back. SCIM does not require order to be preserved, but
    -- a listing that reshuffles on every read makes a diff-based client rewrite
    -- the resource forever.
    ordinal     INT              NOT NULL,

    -- 256 code points.
    value       VARCHAR(1024)    NOT NULL,

    -- 'work', 'home', 'other' or absent; RFC 7643's canonical values are not a
    -- closed set for a type sub-attribute, so this is not constrained here, and a
    -- connector may send any text. 32 code points.
    type        VARCHAR(128),

    is_primary  BIT              NOT NULL,

    -- The type an absent one is counted as for uniqueness: the empty string. A
    -- persisted computed column, because SQL Server indexes columns and not
    -- expressions; the application never names it.
    type_or_empty AS COALESCE(type, '') PERSISTED,

    CONSTRAINT pk_scim_user_emails PRIMARY KEY (resource_id, ordinal),

    CONSTRAINT fk_scim_user_emails_user
        FOREIGN KEY (resource_id) REFERENCES scim_users (resource_id) ON DELETE CASCADE
);

-- `(user, type, value)` uniqueness, with an absent type treated as a value of its
-- own rather than as "distinct from everything": an absent type is indexed as the
-- empty string, so two typeless copies of the same address collide. The domain
-- de-duplicates before writing; this index is what makes that a property of the
-- data. Its key is at most 16 + 128 + 1024 bytes, under SQL Server's 1700-byte
-- limit for a non-clustered index.
CREATE UNIQUE INDEX uq_scim_user_emails_type_value
    ON scim_user_emails (resource_id, type_or_empty, value);

-- At most one primary email per User. A filtered index rather than a trigger or a
-- check: the rule is "no two rows", which is what a unique index says.
CREATE UNIQUE INDEX uq_scim_user_emails_one_primary
    ON scim_user_emails (resource_id)
    WHERE is_primary = 1;

-- A User's password history: the hashes of its most recent passwords, kept only
-- so a password change can refuse one of them being set again. The password
-- policy refuses a password that matches any of the User's last 3, the current
-- one included, and `scim_users.password_hash` holds only the current one.
--
-- Hashes only, and the same Argon2id hashes the credential is stored as: a
-- candidate is checked by matching it against each row through the password
-- encoder. Nothing here can be read back as a password, and the application never
-- tries. The application trims the history to the newest three on every
-- successful change, and the cascade deletes it with the User, so no deletion
-- path has to remember it.
CREATE TABLE scim_user_password_history (
    id            UNIQUEIDENTIFIER  NOT NULL,
    user_id       UNIQUEIDENTIFIER  NOT NULL,
    -- An Argon2id encoding: ASCII-only.
    password_hash VARCHAR(256)      NOT NULL,
    -- When this password was set, UTC. What "most recent" is ordered by.
    set_at        DATETIMEOFFSET(6) NOT NULL,
    CONSTRAINT pk_scim_user_password_history PRIMARY KEY (id),
    CONSTRAINT fk_scim_user_password_history_user
        FOREIGN KEY (user_id) REFERENCES scim_users (resource_id) ON DELETE CASCADE
);

-- Every read and every trim is "this User's rows, newest first".
CREATE INDEX ix_scim_user_password_history_user
    ON scim_user_password_history (user_id, set_at DESC);

-- Groups join `scim_resources` rather than generating ids of their own, which is
-- how a Group and a User are guaranteed never to collide.
CREATE TABLE scim_groups (
    -- The resource id IS the Group's primary key, exactly as it is a User's.
    resource_id             UNIQUEIDENTIFIER NOT NULL,

    -- As the connector sent it, case and all. What is rendered back. 256 code
    -- points.
    display_name            VARCHAR(1024)    NOT NULL,

    -- The normalized form uniqueness is decided on, stored for the reason
    -- `scim_users.normalized_user_name` is: the normalization rule belongs to the
    -- domain, and a functional index would be a second implementation of it in
    -- SQL that could drift from the first. 256 code points.
    normalized_display_name VARCHAR(1024)    NOT NULL,

    CONSTRAINT pk_scim_groups PRIMARY KEY (resource_id),

    -- RFC 7643 does not require `displayName` to be unique. It is made unique
    -- here anyway, because this directory derives AUTHORITY from a Group: two
    -- Groups a human reads as the same name is how an administrator grants
    -- membership of the wrong one. Case-insensitively, so the two cannot differ
    -- only in case.
    CONSTRAINT uq_scim_groups_normalized_display_name UNIQUE (normalized_display_name),

    CONSTRAINT fk_scim_groups_resource
        FOREIGN KEY (resource_id) REFERENCES scim_resources (id) ON DELETE CASCADE
);

-- Membership: direct Users only, and that is a property of the schema rather than
-- of a validation pass.
--
-- `user_id` references `scim_users`, NOT `scim_resources`. The difference is the
-- whole rule: a foreign key to the resource table would accept a Group's id and
-- make nested Groups representable, and it would accept any id that exists at
-- all. Pointing at the User table means "a Group as a member", "an unknown id"
-- and "a deleted User" are all the same refusal — a foreign-key violation — with
-- no prior read that two concurrent writes could both pass.
--
-- Memberships are deleted explicitly, never by cascade (ADR 0015). Both keys are
-- NO ACTION: a cascade from each would reach this table by two paths — resource
-- -> user -> membership, and resource -> group -> membership — which SQL Server
-- refuses. The User and Group delete paths
-- (`ScimUserPersistenceAdapter#deleteById`, `ScimGroupPersistenceAdapter#deleteById`)
-- delete the resource's membership rows in the same transaction, after advancing
-- the versions the lost memberships change and before deleting the resource. A
-- resource row a membership still names is refused, so a delete path that forgot
-- the memberships fails loudly. Every other cascade in the schema is single-path.
CREATE TABLE scim_group_members (
    group_id UNIQUEIDENTIFIER NOT NULL,

    user_id  UNIQUEIDENTIFIER NOT NULL,

    -- The pair is the key, so a User cannot be added to the same Group twice and
    -- there is no second identifier for a membership to be looked up by. A
    -- membership has no attributes of its own: SCIM's `members` sub-attributes
    -- are rendered from the referenced resource, not stored per row.
    CONSTRAINT pk_scim_group_members PRIMARY KEY (group_id, user_id),

    CONSTRAINT fk_scim_group_members_group
        FOREIGN KEY (group_id) REFERENCES scim_groups (resource_id),

    CONSTRAINT fk_scim_group_members_user
        FOREIGN KEY (user_id) REFERENCES scim_users (resource_id)
);

-- The reverse view every User read renders: "which Groups is this User in". The
-- primary key serves the forward direction; this serves the reverse one, which is
-- read on every single User retrieval and would otherwise be a scan of every
-- membership in the directory.
CREATE INDEX ix_scim_group_members_user ON scim_group_members (user_id);

-- The privacy-minimal record a SCIM DELETE leaves behind.
--
-- A deleted User or Group is removed from `scim_resources`, and the deletion
-- removes everything readable about it: the profile, emails, credential, password
-- history, memberships and connector aliases. What survives is this row, and it is
-- deliberately incapable of holding any of those: a UUID, a resource type
-- constrained to the two names the directory issues, and a timestamp. There is no
-- text column a profile value, a credential or a member id could be written into.
-- `ScimDeletionIntegrationTests` asserts exactly that column set, so a later
-- migration adding a free-text column here fails the build rather than a review.
--
-- Never consulted for uniqueness. A former `userName`, `displayName` or connector
-- `externalId` is reusable immediately, per RFC 7644 §3.6; audit resolves every
-- subject by this stable id, so a reused name cannot merge two identities in the
-- trail. Retained independently of audit retention, so the application role may
-- insert and read tombstones but neither change nor remove one.
CREATE TABLE scim_tombstones (
    resource_id   UNIQUEIDENTIFIER  NOT NULL,
    -- ASCII-only: 'User' or 'Group'.
    resource_type VARCHAR(16)       NOT NULL,
    -- When the resource was deleted, UTC.
    deleted_at    DATETIMEOFFSET(6) NOT NULL,
    CONSTRAINT pk_scim_tombstones PRIMARY KEY (resource_id),
    CONSTRAINT ck_scim_tombstones_resource_type
        CHECK (resource_type IN ('User', 'Group'))
);

-- =============================================================================
-- 2. Connectors, their tokens and their externalId aliases
-- =============================================================================

CREATE TABLE scim_connectors (
    id           UNIQUEIDENTIFIER  NOT NULL,

    -- An administrator's label: any text, 200 code points.
    display_name VARCHAR(800)      NOT NULL,

    created_at   DATETIMEOFFSET(6) NOT NULL,

    -- Deletion is a transition, not a DELETE. The row survives so an audit event
    -- that names this connector still resolves to something a year later, and so
    -- a token row's foreign key stays satisfiable. What deletion actually costs
    -- the connector is its tokens and its aliases, both removed in the same
    -- transaction that sets this column.
    deleted_at   DATETIMEOFFSET(6),

    CONSTRAINT pk_scim_connectors PRIMARY KEY (id)
);

CREATE TABLE scim_connector_tokens (
    id                   UNIQUEIDENTIFIER  NOT NULL,

    connector_id         UNIQUEIDENTIFIER  NOT NULL,

    -- The non-secret half of the opaque bearer value, and the only part used to
    -- FIND the row. Looking a token up by its hash would work too, but it would
    -- make every presented value a query key and every failed lookup a timing
    -- signal about the hash space; a separate lookup id keeps the secret half
    -- out of the WHERE clause entirely. Generated by the service: ASCII-only.
    lookup_id            VARCHAR(64)       NOT NULL,

    -- SHA-256 of the COMPLETE presented value, never the secret half alone, and
    -- never the value itself. Unstretched is correct here only because the value
    -- is 256 bits of SecureRandom material rather than a password — see
    -- ConnectorTokenSecret, which says so at the one place that computes it.
    -- 32 bytes; the column leaves room for a longer digest.
    token_hash           VARBINARY(64)     NOT NULL,

    issued_at            DATETIMEOFFSET(6) NOT NULL,

    -- When the token stops being accepted. Rotation may bring this FORWARD to
    -- end an overlap window early; nothing may move it back.
    expires_at           DATETIMEOFFSET(6) NOT NULL,

    -- The expiry the token was issued with, kept so the rule "rotation never
    -- extends an old token's lifetime" is checkable against the row itself
    -- rather than against the code that wrote it.
    original_expires_at  DATETIMEOFFSET(6) NOT NULL,

    revoked_at           DATETIMEOFFSET(6),

    -- Rotation lineage: which token replaced this one. Null on a token that was
    -- never rotated.
    replaced_by_token_id UNIQUEIDENTIFIER,

    CONSTRAINT pk_scim_connector_tokens PRIMARY KEY (id),

    CONSTRAINT uq_scim_connector_tokens_lookup_id UNIQUE (lookup_id),

    CONSTRAINT fk_scim_connector_tokens_connector
        FOREIGN KEY (connector_id) REFERENCES scim_connectors (id),

    CONSTRAINT fk_scim_connector_tokens_replacement
        FOREIGN KEY (replaced_by_token_id) REFERENCES scim_connector_tokens (id),

    -- The lifetime rule as a database constraint. An overlap window that tried
    -- to run past the expiry the token was issued with is refused by the server,
    -- so the rule holds against a future code path that never read the policy
    -- class.
    CONSTRAINT ck_scim_connector_tokens_expiry_never_extended
        CHECK (expires_at <= original_expires_at)
);

-- Deleting a connector revokes every token it holds, and the Admin view lists a
-- connector's tokens; both read by connector.
CREATE INDEX ix_scim_connector_tokens_connector_id
    ON scim_connector_tokens (connector_id);

-- The Permissions a token carries (ADR 0010), a row per Permission, from the same
-- vocabulary a User's Roles grant, spelled as the wire spells them ('user:read').
-- A child table because SQL Server has no arrays, and the table keeps the
-- constraint the database enforced on the array column.
--
-- The pair is the key, so a token cannot carry a Permission twice. An empty set is
-- no rows: it authenticates and may read discovery but nothing else.
--
-- The CHECK: a token can carry only the four directory Permissions, because the
-- rest of the vocabulary guards the application chain, which no bearer token
-- reaches. The application refuses the others first; this is the last line, so a
-- write path that forgot the rule cannot store a token that would mean more the
-- day a route began to honour it.
--
-- ON DELETE CASCADE from the token: a Permission is meaningless without its token,
-- and this is the table's only cascade path. No code path deletes a token today
-- (revocation and connector deletion are updates), but whichever one does later
-- takes the rows with it.
CREATE TABLE scim_connector_token_permissions (
    token_id   UNIQUEIDENTIFIER NOT NULL,

    -- ASCII-only: one of the four names the CHECK allows.
    permission VARCHAR(32)      NOT NULL,

    CONSTRAINT pk_scim_connector_token_permissions PRIMARY KEY (token_id, permission),

    CONSTRAINT fk_scim_connector_token_permissions_token
        FOREIGN KEY (token_id) REFERENCES scim_connector_tokens (id) ON DELETE CASCADE,

    CONSTRAINT ck_scim_connector_token_permissions_directory_permission
        CHECK (permission IN ('user:read', 'user:write', 'group:read', 'group:write'))
);

-- The connector-scoped externalId aliases. A relation of its own is what makes
-- "deleting a connector removes all of its aliases" a statement about data rather
-- than about intent.
CREATE TABLE scim_external_ids (
    connector_id UNIQUEIDENTIFIER NOT NULL,

    resource_id  UNIQUEIDENTIFIER NOT NULL,

    -- Case-exact, per RFC 7643. Duplicate values across resources within one
    -- connector are allowed, so there is no unique constraint on the value. 256
    -- code points.
    external_id  VARCHAR(1024)    NOT NULL,

    CONSTRAINT pk_scim_external_ids PRIMARY KEY (connector_id, resource_id),

    CONSTRAINT fk_scim_external_ids_connector
        FOREIGN KEY (connector_id) REFERENCES scim_connectors (id),

    -- ON DELETE CASCADE because an alias is meaningless without its resource: a
    -- deleted resource's aliases are gone for the same reason a deleted
    -- connector's are, and leaving them behind would make a reused alias value
    -- resolve to a resource that returns 404.
    CONSTRAINT fk_scim_external_ids_resource
        FOREIGN KEY (resource_id) REFERENCES scim_resources (id) ON DELETE CASCADE
);

-- Connector-scoped filtering on externalId, which is the only way this relation
-- is read on the request path. A key of at most 16 + 1024 bytes.
CREATE INDEX ix_scim_external_ids_value
    ON scim_external_ids (connector_id, external_id);

-- =============================================================================
-- 3. The audit trail
-- =============================================================================

-- Every string here but `role_name` is a value from the service's own closed
-- vocabulary, never one a caller submitted, so they are ASCII-only and keep their
-- lengths; the unbounded ones are VARCHAR(MAX).
CREATE TABLE audit_events (
    id                   UNIQUEIDENTIFIER  NOT NULL,

    occurred_at          DATETIMEOFFSET(6) NOT NULL,

    -- What happened, as this service's own vocabulary: LOGIN_SUCCESS,
    -- LOCKOUT_SET, ACCOUNT_DISABLE and so on. A closed set, never free text.
    operation            VARCHAR(64)       NOT NULL,

    -- SUCCESS or FAILURE. The outcome of the operation, not of recording it.
    outcome              VARCHAR(16)       NOT NULL,

    -- Who acted and who was acted on, both as a stable id. Null actor: nobody
    -- was authenticated (a rejected login). Null subject: the operation named no
    -- existing resource (a login against a username that names no User) —
    -- recording anything else there would be recording the submitted username,
    -- which is half a credential.
    actor_id             UNIQUEIDENTIFIER,
    subject_id           UNIQUEIDENTIFIER,

    resource_type        VARCHAR(64)       NOT NULL,
    resource_id          UNIQUEIDENTIFIER,

    -- The attribute paths the operation changed, comma-separated, drawn from a
    -- fixed vocabulary in the application. A list rather than a related table
    -- because the values are short constants and nothing queries them
    -- individually; if a query ever needs one path at a time, that is the
    -- migration that normalises it.
    changed_paths        VARCHAR(MAX),

    -- How the triggering request ended, classified: ok, client_error,
    -- server_error. Paired with error_code, which is a type or reason name from
    -- the service's own code — never a message, which is written for a human and
    -- may grow a submitted value in it later.
    status_class         VARCHAR(32)       NOT NULL,
    error_code           VARCHAR(128),

    -- The triggering request, identified the way it can be without naming a
    -- subject: the method, the matched route TEMPLATE (never the resolved path,
    -- which can carry an identifier in it), and the correlation id minted for
    -- that request.
    http_method          VARCHAR(16),
    http_path            VARCHAR(512),
    request_id           VARCHAR(128),

    -- A bulk read — a query of the User or Group collection, or a base search
    -- across both — is audited as one event carrying what it returned and the
    -- SHAPE of its filter, so a full-directory sync or a zero-result probe by a
    -- compromised token is distinguishable from ordinary traffic. Every other
    -- operation leaves both NULL.
    --
    -- How many resources the response carried. Not totalResults: what a token
    -- was HANDED is the exposure, and a count=0 probe learns a total without
    -- receiving anyone.
    result_count         INT,

    -- The filter with every literal replaced by `?`: canonical attribute paths,
    -- operators and logical structure only, e.g.
    -- `(userName eq ? and emails[type eq ?])`. Rendered by the application from a
    -- closed vocabulary; a filter's VALUES are exactly what the trail must not
    -- become a copy of. Bounded in practice by the filter's own node limit (100
    -- expressions).
    filter_shape         VARCHAR(MAX),

    -- The Role a membership change on a mapped Group granted or revoked. The
    -- Group's id alone does not say which power changed hands, because the role
    -- mapping that turns it into a Role is deployment configuration and can be
    -- replaced by the next deploy; so the event carries the Role's NAME as the
    -- mapping stated it when the change happened. A name from deployment
    -- configuration, never a value a caller submitted, but any text the mapping
    -- holds: Unicode-capable. Unbounded: the mapping does not bound a Role's
    -- name, and a fail-closed append that a long name could refuse would roll
    -- back the membership change it records.
    role_name            VARCHAR(MAX),

    -- The Permissions a token was issued or rotated with, or — on a refused
    -- escalation — the Permissions that were asked for. Names from the closed
    -- vocabulary in code, comma-joined as changed_paths is, never a value a
    -- caller submitted unchecked: a name that is no Permission is refused before
    -- anything is recorded. NULL for every other event.
    permissions          VARCHAR(MAX),

    -- How a Login was attempted (Epic Login, D15): `password` for password
    -- Login, `sso` for an EHR launch from Epic. Carried by LOGIN_SUCCESS and
    -- LOGIN_FAILURE only, and NULL for every other event. A value from the
    -- closed vocabulary in code (AuditLoginMethod), never one a caller submitted.
    login_method         VARCHAR(16),

    -- The MFA factor an Epic Login was made with (Epic Login, D17):
    -- `idp-attested` while the Epic organisation's MFA is an attestation, or the
    -- RFC 8176 factor the id_token's `amr` named once the evidence is required.
    -- Carried by an Epic LOGIN_SUCCESS only, and NULL for every other event — a
    -- password Login's included. A value from the closed vocabulary in code
    -- (AuditMfaFactor), never one Epic sent.
    mfa_factor           VARCHAR(16),

    -- A connector token event names the token it concerns. The connector stays
    -- the event's subject and resource — it is what an administrator
    -- investigates, and what survives a rotation — and these two columns say
    -- WHICH of its tokens: two rotations of one token, or a refusal of a token
    -- already revoked, could not be told apart otherwise. Both are stable ids,
    -- never a token's value or its digest, and neither is a foreign key:
    -- `audit_events` has none, so a recorded event outlives whatever it names and
    -- an append can never wait on, or deadlock with, the row it is about.
    --
    --   token_id              the token issued, rotated or revoked, or the one a
    --                         refused rotation addressed. NULL for every other
    --                         event, and for a refused issue, which addressed no
    --                         token.
    --   replacement_token_id  the token a rotation minted to replace token_id.
    --                         NULL for every other event.
    token_id             UNIQUEIDENTIFIER,
    replacement_token_id UNIQUEIDENTIFIER,

    -- NONCLUSTERED: the table is clustered on (occurred_at, id) below instead.
    CONSTRAINT pk_audit_events PRIMARY KEY NONCLUSTERED (id),

    CONSTRAINT ck_audit_events_result_count_non_negative
        CHECK (result_count IS NULL OR result_count >= 0),

    CONSTRAINT ck_audit_events_login_method
        CHECK (login_method IS NULL OR login_method IN ('password', 'sso')),

    CONSTRAINT ck_audit_events_mfa_factor
        CHECK (mfa_factor IS NULL OR mfa_factor IN (
            'idp-attested', 'mfa', 'otp', 'hwk', 'swk', 'sms', 'tel', 'sc', 'fpt',
            'face', 'iris', 'retina', 'vbm'))
);

-- Clustered on (occurred_at, id) rather than on the random-UUID key (ADR 0015).
-- A random clustered key fragments on every insert into the highest-volume table,
-- while retention deletes by age and the listing orders by it, so both become
-- range operations on the clustered order. `id` makes the key unique.
CREATE UNIQUE CLUSTERED INDEX ix_audit_events_occurred_at
    ON audit_events (occurred_at, id);

-- The history of one subject is read by age.
CREATE INDEX ix_audit_events_subject_id ON audit_events (subject_id);

-- =============================================================================
-- 4. Scheduled-job serialization and showcase data
-- =============================================================================

-- One lock row per scheduled job (ADR 0005). A run takes its own row inside the
-- run's transaction and holds it until that transaction ends. A second run of the
-- SAME job — on this instance or another — finds the row locked and skips; a run
-- of another job locks a different row and is never blocked by it.
--
-- A row per job rather than an application lock keyed by a name: the set of jobs
-- that can be serialized is visible in the schema. ASCII-only names from the code.
CREATE TABLE scheduled_job_locks (
    job_name VARCHAR(64) NOT NULL,
    CONSTRAINT pk_scheduled_job_locks PRIMARY KEY (job_name)
);

INSERT INTO scheduled_job_locks (job_name) VALUES
    ('dormancy'),
    ('audit-retention');

CREATE TABLE user_counters (
    user_id UNIQUEIDENTIFIER NOT NULL,
    count   BIGINT           NOT NULL,
    CONSTRAINT pk_user_counters PRIMARY KEY (user_id),
    CONSTRAINT fk_user_counters_user
        FOREIGN KEY (user_id) REFERENCES scim_users (resource_id) ON DELETE CASCADE
);

-- =============================================================================
-- 5. Database principals and the append-only guard
-- =============================================================================

-- Three logins connect, each a member of exactly the role its work needs, so the
-- server enforces each one's privileges and nothing on a connection can widen them
-- (ADR 0015, "Runtime model"):
--
--   the app login        member of `backend_app`; the request and audit append pools
--   the retention login  member of `backend_audit_retention`; the retention pool
--   the owner login      owns the database and runs these migrations, and nothing
--                        else
--
-- The logins are server principals, created with their passwords by whatever
-- creates the database (the test container setup, the local init, the deployed
-- bootstrap); this migration creates only the database's own principals: the two
-- roles, and a database user for each of the two runtime logins.
--
-- Three mechanisms make the audit table append-only, because each covers a hole
-- the others leave:
--
--   1. GRANTs. `backend_app` holds INSERT and SELECT on `audit_events` and nothing
--      else, so a stray UPDATE or DELETE from application code is refused by the
--      server (error 229) before any row is read.
--   2. An INSTEAD OF UPDATE, DELETE trigger. Grants say nothing about the
--      database's owner — a migration, a console session, a deployment that
--      connected with the wrong credential. The trigger refuses every UPDATE from
--      every principal, and every DELETE unless its caller is a member of the
--      retention role, so the append-only property survives a misconfiguration
--      instead of depending on one being absent.
--   3. `backend_audit_retention`, which holds SELECT and DELETE — no UPDATE, so the
--      grant and the trigger agree — and is the one role the trigger admits.
--
-- A later migration that adds a table the application writes must grant
-- `backend_app` on it; without the grant the application cannot read it. That is
-- the cost of a least-privilege runtime role and is deliberate.

CREATE ROLE backend_app;
CREATE ROLE backend_audit_retention;

-- The two login names arrive as Flyway placeholders, never as literals, because
-- they are the deployment's; never their passwords. `MigrationLoginNames` refuses
-- any name that does not match ^[a-z][a-z0-9_]{2,30}$ before Flyway runs a
-- statement, and each is quoted here with QUOTENAME as an identifier, so a
-- placeholder cannot carry SQL into this file. Each database user is named after
-- its login.
DECLARE @app_login SYSNAME = N'${app_login}';
DECLARE @retention_login SYSNAME = N'${retention_login}';
DECLARE @principals NVARCHAR(MAX) =
    N'CREATE USER ' + QUOTENAME(@app_login) + N' FOR LOGIN ' + QUOTENAME(@app_login)
        + N' WITH DEFAULT_SCHEMA = dbo;'
    + N' ALTER ROLE backend_app ADD MEMBER ' + QUOTENAME(@app_login) + N';'
    + N' CREATE USER ' + QUOTENAME(@retention_login) + N' FOR LOGIN '
        + QUOTENAME(@retention_login) + N' WITH DEFAULT_SCHEMA = dbo;'
    + N' ALTER ROLE backend_audit_retention ADD MEMBER ' + QUOTENAME(@retention_login) + N';';
EXEC (@principals);

-- The application's own privileges: full DML on the tables it owns the lifecycle
-- of, and narrower grants where the table's rule is narrower.
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_resources                   TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_users                       TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_user_emails                 TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_groups                      TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_group_members               TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_connectors                  TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_connector_tokens            TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_connector_token_permissions TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_external_ids                TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_counters                    TO backend_app;
GRANT SELECT, INSERT, DELETE         ON scim_user_password_history       TO backend_app;
-- Tombstones may be written and read, never changed or removed.
GRANT SELECT, INSERT                 ON scim_tombstones                  TO backend_app;
GRANT SELECT, INSERT                 ON audit_events                     TO backend_app;
-- A job's row lock is an update lock, taken by a statement the role may run only
-- with UPDATE as well as SELECT. No INSERT or DELETE: the job set is fixed by
-- migrations, so the runtime role cannot remove the row a job serializes on.
GRANT SELECT, UPDATE                 ON scheduled_job_locks              TO backend_app;
GRANT SELECT                         ON flyway_schema_history            TO backend_app;

-- The retention role's privileges: the audit table alone, and the one verb the
-- application is refused. SELECT because a deletion by age has to find the rows
-- first. No UPDATE: retention deletes what has aged out and changes nothing.
GRANT SELECT, DELETE ON audit_events TO backend_audit_retention;
GO

-- The append-only guard.
--
--   UPDATE is refused from every principal: no audit row is ever changed.
--   DELETE is admitted only from a caller that is a member of
--   `backend_audit_retention` and is not a member of `db_owner`. The second half
--   is what refuses the database's owner, which administers every role and so
--   could make itself a member: administering the role is not being the job it is
--   reserved for. A sysadmin enters the database as its owner and is refused too.
--
-- INSTEAD OF, so the trigger decides before any row changes: it raises its own
-- refusal (THROW, error 50001 — a number of this schema's own, so a log reader
-- tells it from a permission refusal), or performs the admitted DELETE itself.
-- It runs as its caller, so IS_MEMBER answers for the login that issued the
-- statement; the DELETE inside is reached through the table's own ownership chain,
-- so it needs no grant beyond the one the caller already used. The refusal names
-- the login and the verb and quotes no row.
CREATE TRIGGER audit_events_append_only
    ON audit_events
    INSTEAD OF UPDATE, DELETE
AS
BEGIN
    SET NOCOUNT ON;
    -- An UPDATE names the columns it sets, even when it matches no row; a DELETE
    -- names none. So every UPDATE is told from a DELETE, not only one that found
    -- a row.
    DECLARE @updating BIT =
        CASE WHEN DATALENGTH(COLUMNS_UPDATED()) > 0 THEN 1 ELSE 0 END;
    IF @updating = 1
        OR IS_MEMBER(N'backend_audit_retention') IS DISTINCT FROM 1
        OR IS_MEMBER(N'db_owner') = 1
    BEGIN
        DECLARE @refusal NVARCHAR(2048) = CONCAT(
            N'audit_events is append-only: ', ORIGINAL_LOGIN(), N' may not ',
            CASE WHEN @updating = 1 THEN N'UPDATE' ELSE N'DELETE' END,
            N' a recorded event');
        THROW 50001, @refusal, 1;
    END;
    DELETE recorded
      FROM audit_events AS recorded
      JOIN deleted AS aged_out ON aged_out.id = recorded.id;
END;
