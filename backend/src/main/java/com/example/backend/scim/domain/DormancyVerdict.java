package com.example.backend.scim.domain;

/**
 * What the dormancy job owes one User at one instant (ADR 0007): nothing, the lockout, or the
 * lockout and role revocation together.
 *
 * <p>Decided by {@link DormancyPolicy#verdict}, which owns the whole decision — which instant the
 * User's dormancy is measured from, both cutoffs, and the comparison — so the job asks this one
 * question for both of its steps and holds no copy of the rule. Because the windows are ordered
 * (role revocation is the later step), a User past the role-revocation window is always past the
 * lockout window too: there is no "role revocation without lockout".
 */
public enum DormancyVerdict {

    /** Inside both windows, or exempt: the job leaves the User alone. */
    NOT_DUE,

    /** Past the lockout window only: locked, its Roles kept. */
    LOCKOUT,

    /** Past the role-revocation window: locked, and every mapped Group membership removed. */
    LOCKOUT_AND_ROLE_REVOCATION;

    /** Whether the lockout step applies. */
    public boolean locksOut() {
        return this != NOT_DUE;
    }

    /** Whether the role-revocation step applies. */
    public boolean revokesRoles() {
        return this == LOCKOUT_AND_ROLE_REVOCATION;
    }
}
