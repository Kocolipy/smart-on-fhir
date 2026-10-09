package com.example.backend.auth.application;

/**
 * Whether the change-required flag confines the session a Login is building. One type for both
 * halves of a Login — the authorities {@link LoginIdentityService} issues and the dormancy basis
 * {@link LoginAttemptService} moves — so the two cannot disagree about which Logins it confines
 * (ADR 0008 addendum).
 */
enum Confinement {
    /** A password Login: the flag marks the very credential it presented. */
    BY_CHANGE_REQUIRED_FLAG,
    /** An Epic Login: it presented no password of ours, so the flag does not apply (D20). */
    NONE;

    /** Whether a Login of this kind, by a User whose flag is as given, builds a confined session. */
    boolean confines(boolean passwordChangeRequired) {
        return this == BY_CHANGE_REQUIRED_FLAG && passwordChangeRequired;
    }
}
