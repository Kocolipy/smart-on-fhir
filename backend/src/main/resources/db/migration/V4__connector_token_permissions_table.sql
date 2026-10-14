-- A connector token's Permissions move from an array column to a child table
-- (ADR 0015). SQL Server has no arrays, so `scim_connector_tokens.permissions
-- TEXT[]` has no translation; a row per Permission does, and it is the same
-- shape on both engines.
--
-- Each row is one Permission one token carries, spelled as the wire spells it
-- ('user:read'). The pair is the key, so a token cannot carry a Permission
-- twice. An empty set is no rows: it authenticates and may read discovery but
-- nothing else, exactly as the empty array did.
--
-- The CHECK is the one the array column carried, moved to the row it now
-- governs: a token can carry only the four directory Permissions, because the
-- rest of the vocabulary guards the application chain, which no bearer token
-- reaches. The application refuses the others first; this is the last line, so
-- a write path that forgot the rule cannot store a token that would mean more
-- the day a route began to honour it. `permission` is NOT NULL, which is what
-- the array check's `array_position(permissions, NULL) IS NULL` said.
--
-- ON DELETE CASCADE from the token: a Permission is meaningless without its
-- token, and this is the table's only cascade path, so SQL Server accepts it.
-- No code path deletes a token today (revocation and connector deletion are
-- updates), but whichever one does later takes the rows with it.
CREATE TABLE scim_connector_token_permissions (
    token_id   UUID        NOT NULL,

    permission VARCHAR(32) NOT NULL,

    CONSTRAINT pk_scim_connector_token_permissions PRIMARY KEY (token_id, permission),

    CONSTRAINT fk_scim_connector_token_permissions_token
        FOREIGN KEY (token_id) REFERENCES scim_connector_tokens (id) ON DELETE CASCADE,

    CONSTRAINT ck_scim_connector_token_permissions_directory_permission
        CHECK (permission IN ('user:read', 'user:write', 'group:read', 'group:write'))
);

-- Every stored token keeps exactly the Permissions it had. The array check
-- guaranteed each element is one of the four and none is NULL, so every row
-- this writes satisfies the new check.
INSERT INTO scim_connector_token_permissions (token_id, permission)
SELECT DISTINCT t.id, p.permission
  FROM scim_connector_tokens t
 CROSS JOIN LATERAL unnest(t.permissions) AS p (permission);

-- The array check goes with the array.
ALTER TABLE scim_connector_tokens
    DROP CONSTRAINT ck_scim_connector_tokens_directory_permissions,
    DROP COLUMN permissions;

-- The same grant `scim_connector_tokens` carries (V1, section 5): the
-- application writes a token's Permissions with the token. Without it the
-- least-privilege runtime role could not read them.
GRANT SELECT, INSERT, UPDATE, DELETE ON scim_connector_token_permissions TO backend_app;
