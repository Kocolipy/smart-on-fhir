-- Groups carry Permissions (ADR 0015). A User's Permissions are the baseline
-- plus the union of the Permissions of the Groups it is a DIRECT member of; a
-- Group with no row here confers nothing. The configured role mapping, its Roles
-- and its hash are gone, so a Group created over SCIM can be given access
-- without a config change and a redeploy. Nothing here is set over SCIM: only
-- the Superuser sets a Group's Permissions (#159), and until then they come from
-- the `local` profile's development fixtures (authorization.yaml).
--
-- The Admin group carries no row. Its direct members are the Superuser, an
-- authority expanded in code at sign-in, which is not a Permission and so cannot
-- be stored here.
--
-- `permission` holds a Permission's NAME from the closed vocabulary in code
-- (authorization.domain.Permission). There is deliberately no CHECK listing the
-- names: the vocabulary is code's, and a CHECK would be a second copy of it that
-- a code change could silently disagree with. Startup reads every row instead,
-- and refuses to start when one names a Permission that does not exist or that
-- no Group may carry (GroupPermissionValues), naming each. Removing a Permission
-- from code therefore needs a migration that deletes its rows.
--
-- ON DELETE CASCADE from the Group: a Group's Permissions are meaningless
-- without it. SCIM Group DELETE removes the Group's `scim_resources` row, which
-- cascades to `scim_groups` and from there to here — one path, which SQL Server
-- accepts (memberships, which a second path would reach, are deleted explicitly;
-- see V1). So deleting a Group takes its Permissions with it, and nothing names
-- a deleted Group afterwards: the next startup has nothing to refuse.
CREATE TABLE group_permission (
    group_id   UNIQUEIDENTIFIER NOT NULL,

    -- ASCII-only: a name from the vocabulary in code.
    permission VARCHAR(32)      NOT NULL,

    -- The pair is the key, so a Group carries a Permission at most once.
    CONSTRAINT pk_group_permission PRIMARY KEY (group_id, permission),

    CONSTRAINT fk_group_permission_group
        FOREIGN KEY (group_id) REFERENCES scim_groups (resource_id) ON DELETE CASCADE
);

-- What the application needs today, and no more: it reads every Group's
-- Permissions at sign-in, in the Groups projection and at startup, and the
-- development fixtures insert them. Deleting a Group removes its rows by the
-- cascade, which needs no grant here. Replacing a Group's Permissions (#159)
-- grants DELETE when it lands.
GRANT SELECT, INSERT ON group_permission TO backend_app;
