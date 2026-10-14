-- Group memberships are deleted explicitly, never by cascade (ADR 0015).
--
-- V1 gave both member-side foreign keys of `scim_group_members` ON DELETE
-- CASCADE, so deleting a resource reached the membership table by two paths:
-- resource -> user -> membership, and resource -> group -> membership. SQL
-- Server refuses a table reachable by more than one cascade path, so the shape
-- has to change before the engine does, and it changes here, on PostgreSQL,
-- where the existing tests can prove the behaviour did not.
--
-- Both keys become NO ACTION. The User and Group delete paths
-- (`ScimUserPersistenceAdapter#deleteById`, `ScimGroupPersistenceAdapter#deleteById`)
-- delete the resource's membership rows in the same transaction, after advancing
-- the versions the lost memberships change and before deleting the resource. A
-- resource row a membership still names is now refused, so a future delete path
-- that forgot the memberships fails loudly instead of silently relying on a
-- cascade the target engine will not have.
--
-- Every other cascade in the schema is single-path and stays.
ALTER TABLE scim_group_members
    DROP CONSTRAINT fk_scim_group_members_group,
    DROP CONSTRAINT fk_scim_group_members_user;

ALTER TABLE scim_group_members
    ADD CONSTRAINT fk_scim_group_members_group
        FOREIGN KEY (group_id) REFERENCES scim_groups (resource_id),
    ADD CONSTRAINT fk_scim_group_members_user
        FOREIGN KEY (user_id) REFERENCES scim_users (resource_id);
