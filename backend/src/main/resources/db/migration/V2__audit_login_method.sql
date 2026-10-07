-- How a Login was attempted (Epic Login, D15): `password` for password Login,
-- `sso` for an EHR launch from Epic. Carried by LOGIN_SUCCESS and LOGIN_FAILURE
-- only, and NULL for every other event — including every login event recorded
-- before this column existed, whose method was necessarily `password` but which
-- an append-only trail does not rewrite.
--
-- A value from the closed vocabulary in code (AuditLoginMethod), never one a
-- caller submitted. The table-level grants in V1 cover the new column as they
-- cover the rest, and the append-only trigger refuses an UPDATE of it as it
-- refuses one of any other.
ALTER TABLE audit_events ADD COLUMN login_method VARCHAR(16);

ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_login_method
    CHECK (login_method IS NULL OR login_method IN ('password', 'sso'));
