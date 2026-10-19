package com.example.backend.scim.domain;

import com.example.backend.audit.domain.AuditLoginStateAttribute;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What one login-state transition changed: the one shape every transition of the login-state
 * module hands back — its own events and the directory's alike — and the one place its audit rows
 * read their changed paths from.
 *
 * <p>In the directory's domain, beside {@link ScimLoginStateChange}, because the directory's events
 * answer through the {@link ScimUserLoginState} port, which cannot name the login-state module's
 * own types: the dependency between {@code scim} and {@code auth} runs one way.
 * {@link ScimLoginStateChange} is what the store is asked to write; this is what the transition
 * did, which a caller reads back.
 *
 * <p>{@link #moved()} is the mapping from login-state column to audit attribute, spelled once: it
 * reads which column sets moved by the rule the store writes by ({@link ScimLoginStateChange}), so
 * a row names exactly what was written — no more, no less. A transition that moved nothing names
 * nothing.
 *
 * <p>The credential is not a login-state column, so {@link #moved()} never names it whatever
 * {@link #before} and {@link #after} hold. A completed password change says so through
 * {@link #completedAt}, which its row names the credential from.
 *
 * @param before      the User's login state as read under its lock — for a creation, the state of
 *                    a new User holding the credential and nothing else
 * @param after       the login state the transition left stored, or handed back to be stored
 * @param completedAt the instant a password change was completed at — which the credential, the
 *                    dormancy basis and the password history share — or {@code null} when the
 *                    transition completed none
 */
public record LoginStateChange(ScimLoginState before, ScimLoginState after, Instant completedAt) {

    /** A transition from {@code before} to {@code after} that completed no password change. */
    public static LoginStateChange between(ScimLoginState before, ScimLoginState after) {
        return new LoginStateChange(before, after, null);
    }

    /** A transition that left {@code state} as it stood. */
    public static LoginStateChange none(ScimLoginState state) {
        return between(state, state);
    }

    /** The login-state attributes this transition moved; empty when it moved none. */
    public Set<AuditLoginStateAttribute> moved() {
        ScimLoginStateChange columns = new ScimLoginStateChange(before, after);
        Set<AuditLoginStateAttribute> moved = EnumSet.noneOf(AuditLoginStateAttribute.class);
        if (columns.movesFailureRun()) {
            moved.add(AuditLoginStateAttribute.FAILED_LOGIN_ATTEMPTS);
        }
        if (columns.movesLock()) {
            moved.add(AuditLoginStateAttribute.LOCKED_AT);
            moved.add(AuditLoginStateAttribute.LOCK_CAUSE);
        }
        if (columns.movesDormancyBasis()) {
            moved.add(AuditLoginStateAttribute.LAST_AUTHENTICATED_AT);
        }
        if (columns.movesChangeRequired()) {
            moved.add(AuditLoginStateAttribute.PASSWORD_CHANGE_REQUIRED_SINCE);
        }
        return Collections.unmodifiableSet(moved);
    }

    /** Whether this transition moved {@code attribute}. */
    public boolean hasMoved(AuditLoginStateAttribute attribute) {
        return moved().contains(attribute);
    }

    /** Whether this transition completed a password change, replacing the credential. */
    public boolean completedPasswordChange() {
        return completedAt != null;
    }
}
