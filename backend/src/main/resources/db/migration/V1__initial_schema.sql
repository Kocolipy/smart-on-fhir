-- The complete schema. The application is pre-production and a fresh database is
-- the only supported starting state, so the schema is one migration rather than a
-- history of them. A change from here on is a new migration.
--
-- Every id in this schema is assigned by the application before the INSERT, so
-- every layer above the database already agrees on a row's identity before it
-- exists, and the audit event that records a creation can name it. No id column
-- has a server-side default: a row with no id supplied is a defect in the
-- application, not something the database should paper over.

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
    id               UUID        NOT NULL,

    -- 'User' or 'Group', spelled as RFC 7643 spells the resource type, because
    -- this value is rendered into `meta.resourceType` unchanged.
    resource_type    VARCHAR(16) NOT NULL,

    -- Monotonically increasing from 1, rendered as the strong ETag and as
    -- `meta.version`. A BIGINT rather than a timestamp: a version has to change
    -- on every representation change even when two changes land in the same
    -- clock tick, which a timestamp cannot promise.
    version          BIGINT      NOT NULL,

    created_at       TIMESTAMPTZ NOT NULL,

    last_modified_at TIMESTAMPTZ NOT NULL,

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
    -- column test that reads the same for a User and for a Group.
    reserved_name    VARCHAR(32),

    CONSTRAINT pk_scim_resources PRIMARY KEY (id),

    CONSTRAINT ck_scim_resources_type
        CHECK (resource_type IN ('User', 'Group')),

    -- A version below 1 would render an ETag no client could have been given.
    CONSTRAINT ck_scim_resources_version_positive
        CHECK (version >= 1),

    -- Unique so seeding is idempotent against the database rather than against
    -- a prior read: a restart that races another instance cannot produce two
    -- Admin Groups, because the second INSERT violates this.
    CONSTRAINT uq_scim_resources_reserved_name UNIQUE (reserved_name),

    -- A closed set, checked here as well as in the domain enum, because the
    -- value decides whether writes are refused: a typo that stored 'admin_group'
    -- would silently leave the Admin Group unprotected and its authority
    -- underivable.
    CONSTRAINT ck_scim_resources_reserved_name
        CHECK (reserved_name IS NULL
               OR reserved_name IN ('bootstrap-admin', 'admin-group'))
);

COMMENT ON COLUMN scim_resources.reserved_name IS
    'Names a resource the deployment reserves for recovery: the Bootstrap Admin '
    'User or the Admin Group. Non-null means every write and delete against the '
    'resource is refused. NULL for every ordinary resource.';

-- The SCIM User is the only login identity: it owns the profile and the
-- authentication state. Authority is not a column; it is derived from membership
-- of the Admin Group.
CREATE TABLE scim_users (
    -- The resource id IS the user's primary key: a User is a resource, not a row
    -- that points at one, so there is no second identifier to keep in step.
    resource_id                    UUID         NOT NULL,

    -- As the connector sent it, case and all. What is rendered back.
    user_name                      VARCHAR(256) NOT NULL,

    -- The normalized form uniqueness is decided on, stored rather than computed
    -- in the index so the normalization rule lives in one place — the domain —
    -- and the database enforces the result instead of re-implementing the rule in
    -- SQL, where the two could drift.
    normalized_user_name           VARCHAR(256) NOT NULL,

    -- Nullable: a credentialless User is a supported state. It cannot log in
    -- until a password is set, which is a fact about authentication rather than
    -- about whether the identity may exist.
    password_hash                  VARCHAR(256),

    -- No column default. `active` defaults to true on SCIM create, and that
    -- default belongs to the create use case where the RFC puts it; a second copy
    -- here would apply to writes the use case never saw.
    active                         BOOLEAN      NOT NULL,

    display_name                   VARCHAR(256),

    -- The `name` complex attribute, flattened. One row per User rather than a
    -- sub-table, because the sub-attributes are single-valued and the whole
    -- complex value is written and read as a unit.
    formatted_name                 VARCHAR(256),
    family_name                    VARCHAR(256),
    given_name                     VARCHAR(256),
    middle_name                    VARCHAR(256),
    honorific_prefix               VARCHAR(256),
    honorific_suffix               VARCHAR(256),

    preferred_language             VARCHAR(64),
    locale                         VARCHAR(64),
    timezone                       VARCHAR(64),

    -- The authentication state below is application-owned and NOT a SCIM
    -- attribute: it is absent from `/Schemas`, is not filterable, and writing it
    -- does not advance the resource's version or `meta.lastModified`.

    -- The failure run. The default is a genuine one, unlike `active`'s: zero is
    -- the only value a newly provisioned User's failure run can start at, and a
    -- SCIM create names neither this column nor `locked_at`.
    failed_login_attempts          INTEGER      NOT NULL DEFAULT 0,

    -- When a lock was IMPOSED, and nothing about when it ends, because it does
    -- not end on its own. A lock is lifted by an administrator's Unlock, so the
    -- column's presence is the state and no clock is consulted to read it.
    locked_at                      TIMESTAMPTZ,

    -- Why the User is locked (ADR 0011): a failure run (ADR 0007) or dormancy,
    -- imposed by the dormancy job at the lockout window. It sits beside
    -- `locked_at`, set whenever it is set and cleared with it, so a helpdesk
    -- operator can tell a forgotten password from an abandoned account before
    -- unlocking.
    lock_cause                     VARCHAR(16),

    -- When the User last logged in successfully. Nullable, because a User that
    -- has never authenticated has no such instant — the dormancy job then
    -- measure from `scim_resources.created_at`, so a credentialless User is not
    -- treated as dormant the moment it is provisioned. An explicit
    -- inactive-to-active transition also writes it (the reactivation time),
    -- which is what resets the dormancy window for a reactivated User.
    last_authenticated_at          TIMESTAMPTZ,

    -- When the User's current credential was imposed on it by somebody else.
    -- The column's presence IS the flag, as `locked_at`'s presence is the
    -- lockout: a User with a value here must replace its password before it may
    -- do anything but submit that change or log out. Set by every connector
    -- password write, by an Admin's forced change and by an Unlock, and cleared
    -- by nothing but a successful self-service change.
    password_change_required_since TIMESTAMPTZ,

    CONSTRAINT pk_scim_users PRIMARY KEY (resource_id),

    -- Uniqueness among live Users, case-insensitively, as RFC 7643 requires of
    -- `userName`. A deleted User's row is gone, which is what makes a former
    -- userName reusable.
    CONSTRAINT uq_scim_users_normalized_user_name UNIQUE (normalized_user_name),

    -- The last line behind the application: a write path that set one of
    -- `locked_at` and `lock_cause` without the other cannot store it. `NULL IN
    -- (...)` is NULL and a CHECK accepts NULL, so the locked branch spells
    -- `lock_cause IS NOT NULL` explicitly: without it a lock with no cause would
    -- evaluate to `FALSE OR NULL` and be stored.
    CONSTRAINT ck_scim_users_lock_cause
        CHECK ((locked_at IS NULL AND lock_cause IS NULL)
               OR (locked_at IS NOT NULL AND lock_cause IS NOT NULL
                   AND lock_cause IN ('FAILURES', 'DORMANCY'))),

    CONSTRAINT fk_scim_users_resource
        FOREIGN KEY (resource_id) REFERENCES scim_resources (id) ON DELETE CASCADE
);

COMMENT ON COLUMN scim_users.locked_at IS
    'When the User was locked, by its failure run or by the dormancy job; NULL when it is not '
    'locked. A lock has no expiry — only an administrator''s Unlock clears this.';

COMMENT ON COLUMN scim_users.lock_cause IS
    'Why the User is locked: FAILURES or DORMANCY. NULL exactly when locked_at is.';

COMMENT ON COLUMN scim_users.last_authenticated_at IS
    'When the User last logged in successfully, or was last explicitly reactivated; '
    'NULL when neither has happened. The dormancy basis, with created_at as the fallback.';

COMMENT ON COLUMN scim_users.password_change_required_since IS
    'When a password change was last required of the User; NULL when none is. '
    'Cleared only by a successful self-service change.';

CREATE TABLE scim_user_emails (
    resource_id UUID         NOT NULL,

    -- Position in the multi-valued attribute, so the order a connector sent is
    -- the order rendered back. SCIM does not require order to be preserved, but
    -- a listing that reshuffles on every read makes a diff-based client rewrite
    -- the resource forever.
    ordinal     INTEGER      NOT NULL,

    value       VARCHAR(256) NOT NULL,

    -- 'work', 'home', 'other' or absent; RFC 7643's canonical values are not a
    -- closed set for a type sub-attribute, so this is not constrained here.
    type        VARCHAR(32),

    is_primary  BOOLEAN      NOT NULL,

    CONSTRAINT pk_scim_user_emails PRIMARY KEY (resource_id, ordinal),

    CONSTRAINT fk_scim_user_emails_user
        FOREIGN KEY (resource_id) REFERENCES scim_users (resource_id) ON DELETE CASCADE
);

-- `(user, type, value)` uniqueness, with an absent type treated as a value of its
-- own rather than as "distinct from everything". A plain UNIQUE constraint would
-- not do it: in SQL two NULLs are never equal, so two typeless copies of the same
-- address would both be accepted. The domain de-duplicates before writing; this
-- index is what makes that a property of the data.
CREATE UNIQUE INDEX uq_scim_user_emails_type_value
    ON scim_user_emails (resource_id, coalesce(type, ''), value);

-- At most one primary email per User. A partial index rather than a trigger or a
-- check: the rule is "no two rows", which is what a unique index says.
CREATE UNIQUE INDEX uq_scim_user_emails_one_primary
    ON scim_user_emails (resource_id)
    WHERE is_primary;

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
    id            UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    -- When this password was set, UTC. What "most recent" is ordered by.
    set_at        TIMESTAMPTZ  NOT NULL,
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
    resource_id             UUID         NOT NULL,

    -- As the connector sent it, case and all. What is rendered back.
    display_name            VARCHAR(256) NOT NULL,

    -- The normalized form uniqueness is decided on, stored for the reason
    -- `scim_users.normalized_user_name` is: the normalization rule belongs to the
    -- domain, and a functional index would be a second implementation of it in
    -- SQL that could drift from the first.
    normalized_display_name VARCHAR(256) NOT NULL,

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
CREATE TABLE scim_group_members (
    group_id UUID NOT NULL,

    user_id  UUID NOT NULL,

    -- The pair is the key, so a User cannot be added to the same Group twice and
    -- there is no second identifier for a membership to be looked up by. A
    -- membership has no attributes of its own: SCIM's `members` sub-attributes
    -- are rendered from the referenced resource, not stored per row.
    CONSTRAINT pk_scim_group_members PRIMARY KEY (group_id, user_id),

    CONSTRAINT fk_scim_group_members_group
        FOREIGN KEY (group_id) REFERENCES scim_groups (resource_id) ON DELETE CASCADE,

    CONSTRAINT fk_scim_group_members_user
        FOREIGN KEY (user_id) REFERENCES scim_users (resource_id) ON DELETE CASCADE
);

-- The reverse view every User read renders: "which Groups is this User in". The
-- primary key serves the forward direction; this serves the reverse one, which is
-- read on every single User retrieval and would otherwise be a sequential scan of
-- every membership in the directory.
CREATE INDEX ix_scim_group_members_user ON scim_group_members (user_id);

-- The privacy-minimal record a SCIM DELETE leaves behind.
--
-- A deleted User or Group is removed from `scim_resources`, and the cascades
-- remove everything readable about it: the profile, emails, credential, password
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
    resource_id   UUID        NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    -- When the resource was deleted, UTC.
    deleted_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_scim_tombstones PRIMARY KEY (resource_id),
    CONSTRAINT ck_scim_tombstones_resource_type
        CHECK (resource_type IN ('User', 'Group'))
);

-- =============================================================================
-- 2. Connectors, their tokens and their externalId aliases
-- =============================================================================

CREATE TABLE scim_connectors (
    id           UUID         NOT NULL,

    display_name VARCHAR(200) NOT NULL,

    created_at   TIMESTAMPTZ  NOT NULL,

    -- Deletion is a transition, not a DELETE. The row survives so an audit event
    -- that names this connector still resolves to something a year later, and so
    -- a token row's foreign key stays satisfiable. What deletion actually costs
    -- the connector is its tokens and its aliases, both removed in the same
    -- transaction that sets this column.
    deleted_at   TIMESTAMPTZ,

    CONSTRAINT pk_scim_connectors PRIMARY KEY (id)
);

CREATE TABLE scim_connector_tokens (
    id                   UUID         NOT NULL,

    connector_id         UUID         NOT NULL,

    -- The non-secret half of the opaque bearer value, and the only part used to
    -- FIND the row. Looking a token up by its hash would work too, but it would
    -- make every presented value a query key and every failed lookup a timing
    -- signal about the hash space; a separate lookup id keeps the secret half
    -- out of the WHERE clause entirely.
    lookup_id            VARCHAR(64)  NOT NULL,

    -- SHA-256 of the COMPLETE presented value, never the secret half alone, and
    -- never the value itself. Unstretched is correct here only because the value
    -- is 256 bits of SecureRandom material rather than a password — see
    -- ConnectorTokenSecret, which says so at the one place that computes it.
    token_hash           BYTEA        NOT NULL,

    -- The Permissions the token carries (ADR 0010), from the same vocabulary a
    -- User's Roles grant, spelled as the wire spells them ('user:read'). An
    -- empty set authenticates and may read discovery but nothing else.
    permissions          TEXT[]       NOT NULL,

    issued_at            TIMESTAMPTZ  NOT NULL,

    -- When the token stops being accepted. Rotation may bring this FORWARD to
    -- end an overlap window early; nothing may move it back.
    expires_at           TIMESTAMPTZ  NOT NULL,

    -- The expiry the token was issued with, kept so the rule "rotation never
    -- extends an old token's lifetime" is checkable against the row itself
    -- rather than against the code that wrote it.
    original_expires_at  TIMESTAMPTZ  NOT NULL,

    revoked_at           TIMESTAMPTZ,

    -- Rotation lineage: which token replaced this one. Null on a token that was
    -- never rotated.
    replaced_by_token_id UUID,

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
        CHECK (expires_at <= original_expires_at),

    -- A token can carry only the four directory Permissions: the rest of the
    -- vocabulary guards the application chain, which no bearer token reaches. The
    -- application refuses the others first; this is the last line, so a write path
    -- that forgot the rule cannot store a token that would mean more the day a route
    -- began to honour it.
    CONSTRAINT ck_scim_connector_tokens_directory_permissions
        CHECK (permissions <@ ARRAY['user:read', 'user:write', 'group:read', 'group:write']::TEXT[]
               AND array_position(permissions, NULL) IS NULL)
);

-- Deleting a connector revokes every token it holds, and the Admin view lists a
-- connector's tokens; both read by connector.
CREATE INDEX ix_scim_connector_tokens_connector_id
    ON scim_connector_tokens (connector_id);

-- The connector-scoped externalId aliases. A relation of its own is what makes
-- "deleting a connector removes all of its aliases" a statement about data rather
-- than about intent.
CREATE TABLE scim_external_ids (
    connector_id UUID         NOT NULL,

    resource_id  UUID         NOT NULL,

    -- Case-exact, per RFC 7643. Duplicate values across resources within one
    -- connector are allowed, so there is no unique constraint on the value.
    external_id  VARCHAR(256) NOT NULL,

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
-- is read on the request path.
CREATE INDEX ix_scim_external_ids_value
    ON scim_external_ids (connector_id, external_id);

-- =============================================================================
-- 3. The audit trail
-- =============================================================================

CREATE TABLE audit_events (
    id            UUID         NOT NULL,

    occurred_at   TIMESTAMPTZ  NOT NULL,

    -- What happened, as this service's own vocabulary: LOGIN_SUCCESS,
    -- LOCKOUT_SET, ACCOUNT_DISABLE and so on. A closed set, never free text.
    operation     VARCHAR(64)  NOT NULL,

    -- SUCCESS or FAILURE. The outcome of the operation, not of recording it.
    outcome       VARCHAR(16)  NOT NULL,

    -- Who acted and who was acted on, both as a stable id. Null actor: nobody
    -- was authenticated (a rejected login). Null subject: the operation named no
    -- existing resource (a login against a username that names no User) —
    -- recording anything else there would be recording the submitted username,
    -- which is half a credential.
    actor_id      UUID,
    subject_id    UUID,

    resource_type VARCHAR(64)  NOT NULL,
    resource_id   UUID,

    -- The attribute paths the operation changed, comma-separated, drawn from a
    -- fixed vocabulary in the application. A list rather than a related table
    -- because the values are short constants and nothing queries them
    -- individually; if a query ever needs one path at a time, that is the
    -- migration that normalises it.
    changed_paths TEXT,

    -- How the triggering request ended, classified: ok, client_error,
    -- server_error. Paired with error_code, which is a type or reason name from
    -- the service's own code — never a message, which is written for a human and
    -- may grow a submitted value in it later.
    status_class  VARCHAR(32)  NOT NULL,
    error_code    VARCHAR(128),

    -- The triggering request, identified the way it can be without naming a
    -- subject: the method, the matched route TEMPLATE (never the resolved path,
    -- which can carry an identifier in it), and the correlation id minted for
    -- that request.
    http_method   VARCHAR(16),
    http_path     VARCHAR(512),
    request_id    VARCHAR(128),

    -- A bulk read — a query of the User or Group collection, or a base search
    -- across both — is audited as one event carrying what it returned and the
    -- SHAPE of its filter, so a full-directory sync or a zero-result probe by a
    -- compromised token is distinguishable from ordinary traffic. Every other
    -- operation leaves both NULL.
    --
    -- How many resources the response carried. Not totalResults: what a token
    -- was HANDED is the exposure, and a count=0 probe learns a total without
    -- receiving anyone.
    result_count  INTEGER,

    -- The filter with every literal replaced by `?`: canonical attribute paths,
    -- operators and logical structure only, e.g.
    -- `(userName eq ? and emails[type eq ?])`. Rendered by the application from a
    -- closed vocabulary; a filter's VALUES are exactly what the trail must not
    -- become a copy of. Bounded in practice by the filter's own node limit (100
    -- expressions).
    filter_shape  TEXT,

    -- The Role a membership change on a mapped Group granted or revoked. The
    -- Group's id alone does not say which power changed hands, because the role
    -- mapping that turns it into a Role is deployment configuration and can be
    -- replaced by the next deploy; so the event carries the Role's NAME as the
    -- mapping stated it when the change happened. A name from deployment
    -- configuration, never a value a caller submitted. NULL for every event but
    -- ROLE_GRANT and ROLE_REVOKE. TEXT, not a bounded VARCHAR: the mapping does
    -- not bound a Role's name, and a fail-closed append that a long name could
    -- refuse would roll back the membership change it records.
    role_name     TEXT,

    -- The Permissions a token was issued or rotated with, or — on a refused
    -- escalation — the Permissions that were asked for. Names from the closed
    -- vocabulary in code, comma-joined as changed_paths is, never a value a
    -- caller submitted unchecked: a name that is no Permission is refused before
    -- anything is recorded. NULL for every other event.
    permissions   TEXT,

    -- How a Login was attempted (Epic Login, D15): `password` for password
    -- Login, `sso` for an EHR launch from Epic. Carried by LOGIN_SUCCESS and
    -- LOGIN_FAILURE only, and NULL for every other event. A value from the
    -- closed vocabulary in code (AuditLoginMethod), never one a caller submitted.
    login_method  VARCHAR(16),

    -- The MFA factor an Epic Login was made with (Epic Login, D17):
    -- `idp-attested` while the Epic organisation's MFA is an attestation, or the
    -- RFC 8176 factor the id_token's `amr` named once the evidence is required.
    -- Carried by an Epic LOGIN_SUCCESS only, and NULL for every other event — a
    -- password Login's included. A value from the closed vocabulary in code
    -- (AuditMfaFactor), never one Epic sent.
    mfa_factor    VARCHAR(16),

    CONSTRAINT pk_audit_events PRIMARY KEY (id),

    CONSTRAINT ck_audit_events_result_count_non_negative
        CHECK (result_count IS NULL OR result_count >= 0),

    CONSTRAINT ck_audit_events_login_method
        CHECK (login_method IS NULL OR login_method IN ('password', 'sso')),

    CONSTRAINT ck_audit_events_mfa_factor
        CHECK (mfa_factor IS NULL OR mfa_factor IN (
            'idp-attested', 'mfa', 'otp', 'hwk', 'swk', 'sms', 'tel', 'sc', 'fpt',
            'face', 'iris', 'retina', 'vbm'))
);

-- Retention deletes by age, and the history of one subject is read by age too.
CREATE INDEX ix_audit_events_occurred_at ON audit_events (occurred_at);
CREATE INDEX ix_audit_events_subject_id ON audit_events (subject_id);

-- =============================================================================
-- 4. Scheduled-job serialization and showcase data
-- =============================================================================

-- One lock row per scheduled job (ADR 0005). A run takes its own row with
-- `SELECT ... FOR UPDATE SKIP LOCKED` inside the run's transaction and holds it
-- until that transaction ends. A second run of the SAME job — on this instance or
-- another — finds the row locked and skips; a run of another job locks a
-- different row and is never blocked by it.
--
-- A row per job rather than an advisory lock keyed by a hash of the name: two
-- names can never collide on a primary key, and the set of jobs that can be
-- serialized is visible in the schema.
CREATE TABLE scheduled_job_locks (
    job_name VARCHAR(64) NOT NULL,
    CONSTRAINT pk_scheduled_job_locks PRIMARY KEY (job_name)
);

INSERT INTO scheduled_job_locks (job_name) VALUES
    ('dormancy'),
    ('audit-retention');

CREATE TABLE user_counters (
    user_id UUID   NOT NULL,
    count   BIGINT NOT NULL,
    CONSTRAINT pk_user_counters PRIMARY KEY (user_id),
    CONSTRAINT fk_user_counters_user
        FOREIGN KEY (user_id) REFERENCES scim_users (resource_id) ON DELETE CASCADE
);

-- =============================================================================
-- 5. Database roles and the append-only guard
-- =============================================================================

-- Three separate mechanisms make the audit table append-only, because each covers
-- a hole the others leave:
--
--   1. GRANTs. `backend_app` — the role the running application assumes on every
--      connection — holds INSERT and SELECT on `audit_events` and nothing else,
--      so a stray UPDATE or DELETE from application code is refused by the server
--      with SQLSTATE 42501 before any row is read.
--   2. A BEFORE UPDATE OR DELETE trigger. Grants are attached to a role, so they
--      say nothing about a connection that arrives as the table's owner — a
--      migration, a console session, a deployment that forgot to set the runtime
--      role. The trigger refuses the statement whatever role issues it, unless
--      that role is the retention role, so the append-only property survives a
--      misconfiguration instead of depending on one being absent.
--   3. `backend_audit_retention`, which holds the UPDATE/DELETE grant and is the
--      one role the trigger admits. The retention job assumes it for the length
--      of its own transaction and nothing else in the service names it.
--
-- Both roles are NOLOGIN group roles: they carry privileges, not credentials, so
-- nothing here creates a password and no secret enters version control. The
-- login role that runs the migration is granted membership in both, which is what
-- lets the application `SET ROLE backend_app` and the retention job
-- `SET LOCAL ROLE backend_audit_retention`.
--
-- A later migration that adds a table the application writes must grant
-- `backend_app` on it; without the grant the application cannot read it. That is
-- the cost of a least-privilege runtime role and is deliberate.

-- Roles are cluster-wide, so a second database in the same cluster reaches this
-- migration with them already created. Creating them conditionally makes the
-- migration idempotent against the cluster rather than only against this
-- database.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'backend_app') THEN
        CREATE ROLE backend_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'backend_audit_retention') THEN
        CREATE ROLE backend_audit_retention NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO backend_app, backend_audit_retention;

-- The application's own privileges: full DML on the tables it owns the lifecycle
-- of, and narrower grants where the table's rule is narrower.
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_resources        TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_users            TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_user_emails      TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_groups           TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_group_members    TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_connectors       TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_connector_tokens TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_external_ids     TO backend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_counters         TO backend_app;
GRANT SELECT, INSERT, DELETE         ON scim_user_password_history TO backend_app;
-- Tombstones may be written and read, never changed or removed.
GRANT SELECT, INSERT                 ON scim_tombstones       TO backend_app;
GRANT SELECT, INSERT                 ON audit_events          TO backend_app;
-- `FOR UPDATE` needs UPDATE privilege as well as SELECT. No INSERT or DELETE: the
-- job set is fixed by migrations, so the runtime role cannot remove the row a job
-- serializes on.
GRANT SELECT, UPDATE                 ON scheduled_job_locks   TO backend_app;
GRANT SELECT ON flyway_schema_history TO backend_app;

-- The retention role's privileges: the audit table alone, and the two verbs the
-- application is refused. SELECT because a deletion by age has to find the rows
-- first.
GRANT SELECT, UPDATE, DELETE ON audit_events TO backend_audit_retention;

GRANT backend_app, backend_audit_retention TO CURRENT_USER;

-- The append-only guard. `SET search_path` is pinned so the function cannot be
-- redirected at a shadowing object by a caller's own search_path.
CREATE FUNCTION audit_events_refuse_mutation() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF current_user <> 'backend_audit_retention' THEN
        RAISE EXCEPTION
            'audit_events is append-only: % may not % a recorded event',
            current_user, TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- Returning OLD from a BEFORE DELETE lets the delete proceed; returning NEW
    -- from a BEFORE UPDATE leaves the submitted row untouched. Returning NULL
    -- would silently CANCEL the statement, which is the one outcome this guard
    -- must never produce: the retention job would report rows deleted that were
    -- still there.
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_refuse_mutation();
