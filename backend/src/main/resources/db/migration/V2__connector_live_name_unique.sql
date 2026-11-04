-- A connector's display name is held by at most one live connector (#264).
--
-- The index, not a read before the INSERT, is what decides: two creates claiming one
-- name at the same moment both pass a prior read, and the second INSERT then violates
-- this, which the persistence adapter reports as the name being taken. Compared under
-- the database collation, Latin1_General_100_BIN2_UTF8, so equality is exact and
-- case-sensitive: "Epic" and "epic" are two names. The application trims the name
-- before it is stored, so the comparison never turns on edge whitespace.
--
-- Filtered to live rows, as uq_scim_resources_reserved_name is filtered to reserved
-- ones: a deleted connector's row survives for the audit trail, but holds no name, so
-- its name is free to reuse.
--
-- No duplicate cleanup: no deployed database holds connectors yet.
CREATE UNIQUE INDEX uq_scim_connectors_live_display_name
    ON scim_connectors (display_name)
    WHERE deleted_at IS NULL;
