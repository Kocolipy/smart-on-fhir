package com.example.backend.authorization.domain;

/**
 * The log field names that name what a Group confers (ADR 0015), beside the vocabulary they come
 * from, for the session side and the SCIM side alike. The records
 * themselves are built by {@link com.example.backend.observability.LogEvent}'s shapes (ADR 0003);
 * these are only the keys a caller adds to them.
 */
public final class PermissionLogFields {

    /**
     * The Permissions the Group whose membership a connector changed confers, sorted by name and
     * comma-joined. The schema has no field for it, so it is namespaced under {@code app.}.
     * Written only on a change of power — never on an authorization refusal, which must not name a
     * Permission.
     */
    public static final String GROUP_PERMISSIONS = "app.authorization.group_permissions";

    /**
     * The Permissions a dormant User lost to the dormancy job's membership revocation — held
     * before the removals and not after — sorted by name and comma-joined; empty when the Groups
     * removed conferred nothing it does not still hold. Namespaced and written as
     * {@link #GROUP_PERMISSIONS} is, on that one record.
     */
    public static final String PERMISSIONS_LOST = "app.authorization.permissions_lost";

    private PermissionLogFields() {
    }
}
