-- The per-User tally V1 created is retired: no route serves it and no module
-- maps it. V1 is left as it is, so its checksum holds on every database that
-- already ran it; the table goes here instead.
--
-- Dropping the table drops every permission on it, so V1's
-- `GRANT ... TO backend_app` on it goes with it and needs no REVOKE. Its rows
-- went nowhere else: the table was the only place they lived.

DROP TABLE user_counters;
