-- A connector token event names the token it concerns (architecture review
-- 2026-10-11, second pass). The connector stays the event's subject and
-- resource — it is what an administrator investigates, and what survives a
-- rotation — and these two columns say WHICH of its tokens: two rotations of one
-- token, or a refusal of a token already revoked, could not be told apart before.
--
-- Both are stable ids, never a token's value or its digest, and neither is a
-- foreign key: `audit_events` has none, so a recorded event outlives whatever it
-- names and an append can never wait on, or deadlock with, the row it is about.
--
--   token_id              the token issued, rotated or revoked, or the one a
--                         refused rotation addressed. NULL for every other event,
--                         and for a refused issue, which addressed no token.
--   replacement_token_id  the token a rotation minted to replace token_id. NULL
--                         for every other event.
--
-- The table-level grants (`backend_app`: SELECT, INSERT; retention: SELECT,
-- UPDATE, DELETE) cover the new columns as they cover every other, and the
-- append-only trigger is unchanged.
ALTER TABLE audit_events
    ADD COLUMN token_id             UUID,
    ADD COLUMN replacement_token_id UUID;
