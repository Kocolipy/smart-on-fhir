package com.example.backend.authorization.domain;

/**
 * Counts the changes of authorization worth alerting on, beside their audit events: a port, so the
 * directory that makes the change names no metrics library. Counts only — who changed and whom is
 * the audit trail's to carry.
 */
public interface AuthorizationChangeCounts {

    /** A User became the Superuser: it gained direct membership of the Admin group. */
    void superuserGranted();
}
