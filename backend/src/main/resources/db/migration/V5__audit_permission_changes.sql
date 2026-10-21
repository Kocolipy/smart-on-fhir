-- The audit trail speaks in Permissions rather than Roles (ADR 0015). A membership
-- change of power is a PERMISSION_GRANT or PERMISSION_REVOKE recording the User's
-- effective Permissions before and after it and whether it granted or removed the
-- Superuser, and the dormancy job's revocation, which now removes every direct
-- Group membership, is a DORMANCY_MEMBERSHIP_REVOCATION listing the Groups removed.
-- `role_name` goes.
--
-- A clean cut, and a recorded one-off deviation from the Logging standard's
-- audit-integrity rule (ADR 0015, "App-Standards conscious deviations"): the rows
-- already recorded under the old operation names are REWRITTEN, because no legacy
-- operation survives in code — `operation` is read back as `AuditOperation` by
-- name, and the column has no CHECK, so a row left under a removed name would
-- refuse the whole listing. There is no production instance, so no real trail is
-- altered. V1 is left as it is, so its checksum holds on every database that
-- already ran it.
--
-- Grants: none are needed and none change. V1 grants `backend_app` SELECT and
-- INSERT, and `backend_audit_retention` SELECT and DELETE, on the TABLE, so each
-- column added here is covered by exactly the privileges every other column has,
-- and dropping `role_name` takes nothing else with it. Still no UPDATE for anyone.

-- The four new columns, each NULL for every event that is not a membership change.
--
--   permissions_before  the subject User's effective Permissions before the change:
--                       names from the closed vocabulary in code, sorted and
--                       comma-joined as `permissions` is.
--   permissions_after   the same, after the change.
--   superuser_changed   whether the change granted or removed the Superuser.
--   group_ids           every Group a DORMANCY_MEMBERSHIP_REVOCATION removed the
--                       User from, by stable id, comma-joined. A connector's
--                       membership change names its one Group as `resource_id`.
ALTER TABLE audit_events ADD
    permissions_before VARCHAR(MAX),
    permissions_after  VARCHAR(MAX),
    superuser_changed  BIT,
    group_ids          VARCHAR(MAX);
GO

-- The rewrite. V1's append-only trigger refuses every UPDATE from every principal,
-- the owner included, so it is disabled for exactly these three statements and
-- enabled again before the batch ends. The migration runs in one transaction:
-- a failure anywhere rolls the rewrite back and leaves the trigger enabled.
--
-- A recorded Role has no Permissions to translate into here — the role mapping
-- that defined it was deployment configuration — so its NAME moves into the column
-- on the side of the change it was on: what a grant gained is after it, what a
-- revocation lost is before it. A migrated row's other side is empty, and its
-- Superuser marker and removed Groups are unknown, so NULL.
DISABLE TRIGGER audit_events_append_only ON audit_events;

UPDATE audit_events
   SET operation = 'PERMISSION_GRANT', permissions_after = role_name
 WHERE operation = 'ROLE_GRANT';

UPDATE audit_events
   SET operation = 'PERMISSION_REVOKE', permissions_before = role_name
 WHERE operation = 'ROLE_REVOKE';

UPDATE audit_events
   SET operation = 'DORMANCY_MEMBERSHIP_REVOCATION', permissions_before = role_name
 WHERE operation = 'DORMANCY_ROLE_REVOCATION';

ENABLE TRIGGER audit_events_append_only ON audit_events;
GO

ALTER TABLE audit_events DROP COLUMN role_name;
