package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditRefusalReason;

/**
 * The counts a Login's ending moves, by either login method — the one {@code login} counter
 * (ADR 0013, "Metrics") — for {@link LoginOutcomeService} to move without knowing the meter
 * registry: a port, whose adapter is the Micrometer meter.
 */
public interface LoginCounts {

    /** A Login by {@code method} signed a User in. */
    void signedIn(AuditLoginMethod method);

    /** A Login by {@code method} was refused for {@code reason}, as the audit trail recorded it. */
    void refused(AuditLoginMethod method, AuditRefusalReason reason);

    /** An Epic Login could not complete because Epic was unavailable (D23). */
    void unavailable();
}
