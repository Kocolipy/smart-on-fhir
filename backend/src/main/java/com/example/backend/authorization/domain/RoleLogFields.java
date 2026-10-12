package com.example.backend.authorization.domain;

/**
 * The log field names that name a {@link Role} or the {@link RoleMapping} (ADR 0010), beside the
 * vocabulary they come from, for the session side and the SCIM side alike. The records
 * themselves are built by {@link com.example.backend.observability.LogEvent}'s shapes (ADR 0003);
 * these are only the keys a caller adds to them.
 */
public final class RoleLogFields {

    /**
     * The Role a mapped Group's membership change granted or revoked, by its name in the role
     * mapping. The schema has no field for it, so it is namespaced under {@code app.}. Written
     * only on a change of power — never on an authorization refusal, which must not name a Role.
     */
    public static final String ROLE_NAME = "app.authorization.role";

    /** The SHA-256 of the role mapping, on the record that reports it validated at startup. */
    public static final String ROLE_MAPPING_HASH = "app.authorization.mapping_hash";

    private RoleLogFields() {
    }
}
