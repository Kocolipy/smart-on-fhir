package com.example.backend.audit.domain;

/**
 * Raising an operator's attention, for the two failures a request is allowed to
 * outlive and nothing else would report: an audit append that failed, and a
 * Session revocation the session store could not carry out.
 *
 * <p>A port rather than a log call inside the application service because "an
 * alert was raised" is a claim a test has to be able to check without reading log
 * bytes, and because where an alert goes is a deployment decision — a log record
 * a collector alerts on today, a metric or a pager tomorrow — while whether one is
 * raised is a rule of the use case.
 *
 * <p>Nothing here takes a message. An alert names an operation and the type of
 * failure, both from closed sets, so an alert can never be the thing that leaks
 * the value an audit event was careful not to record.
 */
public interface OperationalAlerts {

    /**
     * Reports that an audit event could not be appended, and that the request it
     * belonged to was allowed to finish anyway.
     *
     * @param operation what the unrecorded event was going to say happened
     * @param failure   the failure's own type
     */
    void auditAppendFailed(AuditOperation operation, Class<? extends Throwable> failure);

    /**
     * Reports that the session store failed to end a User's sessions, so sessions that should
     * have ended may still be live. Raised whether or not the failure then reaches the caller:
     * a refusal swallows it to keep its bare answer, and a request that would otherwise succeed
     * answers with an error an operator reads as a fault, not as surviving sessions.
     *
     * @param failure the failure's own type
     */
    void sessionRevocationFailed(Class<? extends Throwable> failure);
}
