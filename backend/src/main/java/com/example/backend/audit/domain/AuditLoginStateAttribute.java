package com.example.backend.audit.domain;

/**
 * The login-state columns of a User a login-state transition can move, as a closed set — the
 * login-state counterpart of {@link AuditUserAttribute}, closed for the same reason: a
 * caller-assembled path list is a place a submitted value could be written.
 *
 * <p>Every login-state event names, of these, exactly the ones its transition moved, read from
 * what the login-state module hands back rather than restated per event
 * ({@code /docs/domain/authentication.md}). The credential is not among them: a changed password
 * is a completed password change, named by {@link AuditUserAttribute#PASSWORD}.
 *
 * <p>Declaration order is the recorded order.
 */
public enum AuditLoginStateAttribute implements AuditAttribute {
    /** The failure run's count. */
    FAILED_LOGIN_ATTEMPTS("failedLoginAttempts"),
    /** When the standing lock was imposed; set and cleared with {@link #LOCK_CAUSE}. */
    LOCKED_AT("lockedAt"),
    /** Why the standing lock was imposed; set and cleared with {@link #LOCKED_AT}. */
    LOCK_CAUSE("lockCause"),
    /** The dormancy basis. */
    LAST_AUTHENTICATED_AT("lastAuthenticatedAt"),
    /** The change-required flag. */
    PASSWORD_CHANGE_REQUIRED_SINCE("passwordChangeRequiredSince");

    private final String path;

    AuditLoginStateAttribute(String path) {
        this.path = path;
    }

    @Override
    public String path() {
        return path;
    }
}
