package com.example.backend.authorization.domain;

/**
 * Counts a Superuser setting a Group's Permissions (ADR 0015), beside its audit event: the
 * administration side's half of the {@code authorization.changes} counter, as
 * {@link AuthorizationChangeCounts} is the directory's. A port of its own because a different
 * use case drives it; the one meter adapter answers both. Counts only — who set what is the
 * audit trail's to carry.
 */
public interface GroupPermissionChangeCounts {

    /**
     * A Superuser set a Group's Permissions: what the Group's direct members hold from their
     * next sign-in was replaced at once for all of them. Counted for every accepted replacement,
     * one that repeats the Group's current set included.
     */
    void groupPermissionsChanged();
}
