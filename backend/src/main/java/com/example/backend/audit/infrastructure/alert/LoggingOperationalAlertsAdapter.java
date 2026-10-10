package com.example.backend.audit.infrastructure.alert;

import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.OperationalAlerts;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Severity;
import com.example.backend.observability.LogEvent.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Raises an operational alert as an {@code ERROR} record in the structured log
 * stream, which is what a collector alerts on.
 *
 * <p>{@code ERROR} is the level and not {@code WARN} on purpose: an audit event
 * that could not be appended means the trail has a hole in it, and the request it
 * belonged to completed anyway. Nobody learns that from anywhere else, so it is
 * not a condition to notice in aggregate — it is one to be told about.
 *
 * <p>Nothing variable reaches the message. Both values are names from closed sets
 * — the operation, and the failure's own class — emitted as structured fields, so
 * the alert cannot become the thing that leaks the value the unwritten event was
 * careful not to record.
 */
@Component
class LoggingOperationalAlertsAdapter implements OperationalAlerts {

    private static final Logger log = LoggerFactory.getLogger(LoggingOperationalAlertsAdapter.class);

    /**
     * What the record says in place of the exception it does not attach. The port hands this
     * adapter the failure's type only — deliberately, see {@link OperationalAlerts} — so there
     * is no exception here to attach, and {@code error.type} is absent rather than invented;
     * {@code event.reason} carries the type instead.
     */
    static final String CAUSE_OMITTED =
            "The alert port carries the failure's type, never the exception; see event.reason.";

    /** {@code error.code}: a fault on this side, as a failed scheduled run is. */
    static final int ERROR_CODE = 500;

    @Override
    public void auditAppendFailed(AuditOperation operation, Class<? extends Throwable> failure) {
        // High severity: a missing audit record is a compliance gap whoever reads the alert,
        // so it is routed as one independently of the level.
        LogEvent.error(log, Operation.AUDIT_APPEND, ERROR_CODE, ErrorCategory.DATABASE,
                        Category.DATABASE, Type.ERROR)
                .addKeyValue(LogEvent.SEVERITY, Severity.HIGH.value())
                .addKeyValue(LogEvent.REASON, failure.getSimpleName())
                .addKeyValue(LogEvent.AUDIT_OPERATION, operation.name())
                .addKeyValue(LogEvent.ERROR_CAUSE_OMITTED, CAUSE_OMITTED)
                .log();
    }

    /**
     * High severity for the reason an append failure is: sessions that should have ended may
     * still be acting, and on a refusal nothing but this record says so. The session store is
     * the datastore that failed, so it is classified as one.
     */
    @Override
    public void sessionRevocationFailed(Class<? extends Throwable> failure) {
        LogEvent.error(log, Operation.SESSION_END, ERROR_CODE, ErrorCategory.DATABASE,
                        Category.DATABASE, Type.ERROR)
                .addKeyValue(LogEvent.SEVERITY, Severity.HIGH.value())
                .addKeyValue(LogEvent.REASON, failure.getSimpleName())
                .addKeyValue(LogEvent.ERROR_CAUSE_OMITTED, CAUSE_OMITTED)
                .log();
    }
}
