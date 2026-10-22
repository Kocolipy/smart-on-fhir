package com.example.backend.scim.domain;

/**
 * What the dormancy job owes one User at one instant (ADR 0007): nothing, the lockout, or the
 * lockout and membership revocation together.
 *
 * <p>Decided by {@link DormancyPolicy#verdict}, which owns the whole decision — which instant the
 * User's dormancy is measured from, both cutoffs, and the comparison — so the job asks this one
 * question for both of its steps and holds no copy of the rule. Because the windows are ordered
 * (membership revocation is the later step), a User past the revocation window is always past
 * the lockout window too: there is no "membership revocation without lockout".
 */
public enum DormancyVerdict {

    /** Inside both windows, or exempt: the job leaves the User alone. */
    NOT_DUE,

    /** Past the lockout window only: locked, its Group memberships kept. */
    LOCKOUT,

    /** Past the revocation window: locked, and every Group membership removed. */
    LOCKOUT_AND_MEMBERSHIP_REVOCATION;

    /** Whether the lockout step applies. */
    public boolean locksOut() {
        return this != NOT_DUE;
    }

    /** Whether the Membership revocation step applies. */
    public boolean revokesMemberships() {
        return this == LOCKOUT_AND_MEMBERSHIP_REVOCATION;
    }
}
