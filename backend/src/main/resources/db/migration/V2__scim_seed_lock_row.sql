-- The seeding lock as a row lock (ADR 0015, 2026-10-16 addendum).
--
-- Directory seeding at startup reads what exists and creates what is missing, so
-- two instances starting together must not both read "absent". Each start's
-- seeding transaction takes the update lock on this table's one row
-- (ScimSeedLockAdapter: `WITH (UPDLOCK, ROWLOCK)`, no READPAST) and holds it
-- until it commits, so a second start waits for the first and then reads what
-- it seeded.
--
-- A row rather than an application lock (`sp_getapplock`, which V1's seeding
-- used): on a server collated Latin1_General_100_BIN2_UTF8 — the deployed
-- server's collation — `sp_getapplock` fails on the pinned SQL Server 2022 image
-- with error 17750 (`xp_userlock`: "Could not load the DLL") and returns
-- "granted" for a lock it never took. A row lock is the ordinary lock manager's
-- and works under any server collation. The migration inserts the row, so there
-- is one to lock on a fresh database too, which is when seeding matters.
--
-- One row, named after the lock; ASCII only, so the column keeps its limit.
CREATE TABLE scim_seed_locks (
    lock_name VARCHAR(64) NOT NULL,
    CONSTRAINT pk_scim_seed_locks PRIMARY KEY (lock_name)
);

INSERT INTO scim_seed_locks (lock_name) VALUES ('ScimSeed');

-- SELECT alone: a locking read (UPDLOCK) needs no more, so the runtime role can
-- take the row's lock and can neither change nor remove the row it serializes on.
GRANT SELECT ON scim_seed_locks TO backend_app;
