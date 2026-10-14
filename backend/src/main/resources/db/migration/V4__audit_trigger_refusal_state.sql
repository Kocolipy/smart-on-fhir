-- The append-only trigger's refusal gets an SQLState of its own (#64, ADR 0004's
-- 2026-10-14 addendum).
--
-- V1 raised it as `insufficient_privilege` (42501), the state a missing grant
-- raises, and V3 kept that state when it moved the guard to role membership. A
-- log reader classifies a database failure by its SQLState (ADR 0003, "The
-- database category mapping"): a permission refusal is `cert/auth`, this
-- service's credential or rights to check, while the trigger's refusal is a
-- `database` failure to investigate — something tried to rewrite the audit
-- trail. One state could not tell the two apart.
--
-- `triggered_data_change_violation` (27000) is the SQL standard's class for a
-- change a trigger refused, so the state says what happened on any engine and
-- quotes nothing. The guard itself is V3's, unchanged: every UPDATE refused, a
-- DELETE admitted only from a member of `backend_audit_retention` that does not
-- administer it, the same message, the same return value, the same pinned
-- search_path. Only the ERRCODE differs. CREATE OR REPLACE keeps the function's
-- identity, so the trigger V1 attached still calls it.
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
            USING ERRCODE = 'triggered_data_change_violation';
    END IF;
    -- Returning OLD from a BEFORE DELETE lets the delete proceed. Returning NULL
    -- would silently CANCEL the statement, which is the one outcome this guard must
    -- never produce: the retention job would report rows deleted that were still
    -- there.
    RETURN OLD;
END
$$;
