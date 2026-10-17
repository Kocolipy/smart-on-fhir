package com.example.backend.auth.application;

/**
 * The log field names the dormancy job's records carry (ADR 0007), beside the job that writes
 * them. The records themselves are built by {@link com.example.backend.observability.LogEvent}'s
 * shapes (ADR 0003); these are only the keys the job adds to them.
 */
public final class DormancyLogFields {

    /** The dormancy lockout window, as an ISO-8601 duration, on the job's startup record. */
    public static final String DORMANCY_LOCKOUT_WINDOW = "dormancy.lockout.window";

    /** The dormancy role-revocation window, as an ISO-8601 duration, on the job's startup record. */
    public static final String DORMANCY_ROLE_REVOCATION_WINDOW = "dormancy.role_revocation.window";

    /** How many Users one dormancy run locked, on its {@code job-end} record. */
    public static final String DORMANCY_LOCKED_COUNT = "dormancy.locked_count";

    /** How many Users one dormancy run revoked the Roles of, on its {@code job-end} record. */
    public static final String DORMANCY_ROLES_REVOKED_COUNT = "dormancy.roles_revoked_count";

    private DormancyLogFields() {
    }
}
