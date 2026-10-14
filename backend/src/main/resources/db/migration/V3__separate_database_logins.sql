-- Separate database logins replace the role switch (#66, ADR 0015 "Runtime model").
--
-- Until now one login connected for everything and switched role on the connection:
-- the request pool to `backend_app`, the retention job to `backend_audit_retention`
-- for one transaction. Each runtime pool now connects as a login of its own that is
-- a member of exactly one role, so the server enforces each pool's privileges and
-- nothing on a connection can widen them:
--
--   the app login        member of `backend_app`; the request and audit append pools
--   the retention login  member of `backend_audit_retention`; the retention pool
--   the owner login      owns the schema and runs these migrations, and nothing else
--
-- The owner login administers both roles (WITH ADMIN OPTION, from the deployment's
-- bootstrap), which is what lets it grant them below; it needs neither role's
-- privileges, since it owns every table.
--
-- The two login names arrive as Flyway placeholders, never as literals, because
-- they are the deployment's. `MigrationLoginNames` refuses any name that does not
-- match ^[a-z][a-z0-9_]{2,30}$ before Flyway runs a statement, and each is quoted
-- here as an identifier, so a placeholder cannot carry SQL into this file.

GRANT backend_app TO "${app_login}";
GRANT backend_audit_retention TO "${retention_login}";

-- No principal may update a recorded event, the retention login included: retention
-- deletes what has aged out and changes nothing. The grant and the trigger below now
-- agree, so an UPDATE is refused by the grant first and by the trigger behind it.
REVOKE UPDATE ON audit_events FROM backend_audit_retention;

-- The append-only guard decides by membership rather than by name. With the role
-- switch gone, `current_user` is the login itself, never `backend_audit_retention`,
-- so the old name check would refuse the retention job as well.
--
--   UPDATE is refused from every principal.
--   DELETE is admitted only from a login whose privileges include the retention
--   role's (pg_has_role … 'USAGE': a member that inherits them) and that does not
--   administer it. The second half is what refuses the owner login: it holds the role
--   WITH ADMIN OPTION so that it can grant it, and on PostgreSQL 15 an administrator
--   is also a member that inherits. Administering the role is not being the job it
--   is reserved for. A superuser holds every role WITH ADMIN OPTION, so it is refused
--   too, as is any login granted the role carelessly beside the right to hand it on.
--
-- `SET search_path` stays pinned, so the function cannot be redirected at a
-- shadowing object by a caller's own search_path.
CREATE OR REPLACE FUNCTION audit_events_refuse_mutation() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        OR NOT pg_has_role(current_user, 'backend_audit_retention', 'USAGE')
        OR pg_has_role(current_user, 'backend_audit_retention', 'MEMBER WITH ADMIN OPTION')
    THEN
        RAISE EXCEPTION
            'audit_events is append-only: % may not % a recorded event',
            current_user, TG_OP
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- Returning OLD from a BEFORE DELETE lets the delete proceed. Returning NULL
    -- would silently CANCEL the statement, which is the one outcome this guard must
    -- never produce: the retention job would report rows deleted that were still
    -- there.
    RETURN OLD;
END
$$;
