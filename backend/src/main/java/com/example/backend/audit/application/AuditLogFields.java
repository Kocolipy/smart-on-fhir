package com.example.backend.audit.application;

/**
 * The log field names the audit trail's own records carry — the retention job's and the alert
 * raised when an event could not be appended — beside the module that writes them. The records
 * themselves are built by {@link com.example.backend.observability.LogEvent}'s shapes (ADR 0003);
 * these are only the keys the audit module adds to them.
 */
public final class AuditLogFields {

    /**
     * Which audit operation a record is about, as an
     * {@link com.example.backend.audit.domain.AuditOperation} name. Carried by the
     * operational alert raised when an event could not be appended, so the alert
     * says which record is missing from the trail.
     */
    public static final String AUDIT_OPERATION = "audit.operation";

    /** The configured audit retention window, as an ISO-8601 duration. */
    public static final String RETENTION_PERIOD = "audit.retention.period";

    /** How many aged-out events one retention run removed. */
    public static final String RETENTION_DELETED_ROWS = "audit.retention.deleted_rows";

    private AuditLogFields() {
    }
}
