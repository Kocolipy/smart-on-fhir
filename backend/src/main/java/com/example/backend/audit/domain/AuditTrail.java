package com.example.backend.audit.domain;

/**
 * The one way anything in this service records that something happened: a caller constructs the
 * {@link Audited} event that says what happened, and appends it.
 *
 * <p><strong>Nothing that crosses this boundary holds a {@link String}.</strong> The one method
 * takes an event, and no event has a {@code String} component — the redaction rule expressed as a
 * signature rather than as a review note, because a username, a password and a bearer value are
 * all strings. {@code ArchitectureTest.the_audit_trail_boundary_admits_no_free_text} holds the
 * shape, and {@code semgrep/rules/service-security.yml} flags an event constructed with a
 * credential-named value.
 *
 * <p>A caller does not choose whether the trail may fail silently. Whether an append is
 * fail-closed or fail-open with an operational alert follows from the event's kind — what the
 * triggering request was going to return, as {@link Audited} documents and
 * {@code /docs/adr/0004-audit-append-failure-semantics.md} decides — and the implementation
 * applies that rule once, to every event.
 */
public interface AuditTrail {

    /**
     * Records {@code event}.
     *
     * <p>An {@link Audited.Accepted} event joins the caller's transaction, or opens one, and a
     * failure propagates, so the change it records rolls back with it. An {@link Audited.Refused}
     * or {@link Audited.Committed} event is appended in a transaction of its own, and a failure
     * raises {@link OperationalAlerts#auditAppendFailed} instead of propagating.
     */
    void append(Audited event);
}
