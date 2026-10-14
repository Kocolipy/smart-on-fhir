package com.example.backend.database;

/**
 * The qualifiers the service's connection beans are injected by, each declared once, here.
 *
 * <p>A connection bean is declared by a configuration and injected by an adapter or a service —
 * the retention pool's template by the retention adapter, the main pool's transaction manager by
 * the scheduled-job run module — and a configuration and a persistence adapter are separate onion
 * adapter layers that may not depend on each other. So the name they meet on lives in neither,
 * but here, beside the module that owns how the service reaches its database
 * ({@code database.config.DatabaseConnections}), and both sides spell it through this class. A
 * name that matched no bean fails the context's start; it can no longer be spelled two ways.
 *
 * <p>Names only: this class knows no bean, no type and no business module.
 */
public final class ConnectionQualifiers {

    /**
     * The main pool's transaction manager: Boot's JPA one, declared under this name by Boot. The
     * scheduled-job lock is taken in its transactions, since only the app login holds the grants
     * on the lock rows (ADR 0005).
     */
    public static final String MAIN_TRANSACTIONS = "transactionManager";

    /** The audit append pool's template, which fail-open appends are written through. */
    public static final String AUDIT_APPEND_JDBC = "auditAppendJdbcTemplate";

    /** The retention pool's template, which the retention adapter deletes through. */
    public static final String AUDIT_RETENTION_JDBC = "auditRetentionJdbcTemplate";

    /** The retention pool's transaction manager, which those deletes commit in. */
    public static final String AUDIT_RETENTION_TRANSACTIONS = "auditRetentionTransactionManager";

    private ConnectionQualifiers() {
    }
}
