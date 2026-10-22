-- A Superuser sets a Group's Permissions (#159, ADR 0015):
-- PUT /api/admin/groups/{id}/permissions replaces the Group's set, which is a
-- DELETE of its rows and an INSERT of the new ones in one transaction. V6
-- granted the application login SELECT and INSERT only, and said this grant
-- would come when the replacement landed. Nothing else deletes a row here: a
-- deleted Group's rows go by the cascade from scim_groups, which needs no grant.
GRANT DELETE ON group_permission TO backend_app;
