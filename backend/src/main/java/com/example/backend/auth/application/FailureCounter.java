package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Clock;

/**
 * Carries out one counted failure against a User: the failure run grows, the Lockout is imposed
 * once the policy's limit is reached, and a newly imposed Lockout is audited and ends the User's
 * Sessions.
 *
 * <p>{@link ScimLoginState} decides what the failure does to the run and whether it locks; this is
 * the one place that carries out what that decision implies. Every counting path calls it — the
 * login path and the self-service password change's wrong current password — and then records its
 * own refusal event, so the order on each is: state persisted, then the Lockout audit and the
 * revocation, then the path's refusal. A path that counted a failure any other way would have to
 * repeat the edge test below, and a copy that forgot the revocation would read like its neighbour.
 *
 * <p>The Bootstrap Admin's failures are counted and never lock, read off the User's own
 * reservation marker. A newly imposed Lockout is the only transition audited here: a failure on a
 * User already locked neither deepens the Lockout nor imposes it again, so it records no second
 * {@code LOCKOUT_SET} and revokes nothing further. The Lockout is permanent until an administrator
 * unlocks it ({@code /docs/adr/0007-permanent-lockout-until-admin-unlock.md}).
 *
 * <p>The revocation is the Session revocation module's ({@link SessionRevocationService}), under
 * {@link SessionRevocationCause#FAILURE_RUN_LOCKOUT}: it runs after the transaction commits,
 * because Redis is not in the transaction and a revocation already performed cannot be undone by a
 * rollback ({@code /docs/adr/0002-revoke-sessions-after-commit.md}), and it is audited under that
 * cause. It is the one revocation a refusal imposes, so a session store that fails is recorded and
 * alerted without changing the path's bare refusal (ADR 0004's 2026-10-10 addendum). The
 * {@code LOCKOUT_SET} append is fail-open, as everything on the failure path is
 * ({@link AuditTrail}).
 *
 * <p>Plain Java and package-private: {@link LoginAttemptService} builds it from the collaborators
 * it already holds, so counting stays where ADR 0001 puts it rather than becoming a bean any module
 * could reach.
 */
final class FailureCounter {

    private final ScimUserRepository users;
    private final SessionRevocationService sessions;
    private final LockoutPolicy policy;
    private final AuditTrail audit;
    private final Clock clock;

    FailureCounter(
            ScimUserRepository users,
            SessionRevocationService sessions,
            LockoutPolicy policy,
            AuditTrail audit,
            Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Counts one failure against {@code user}, persisting the new login state through the narrow
     * login-state write, and on a newly imposed Lockout audits it and revokes every Session of the
     * User once the surrounding transaction commits.
     */
    void count(ScimUser user) {
        ScimLoginState before = user.login();
        ScimLoginState after = user.isExemptFromLockout()
                ? before.withFailureCounted()
                : before.withFailureRecorded(policy, clock.instant());
        users.updateLoginState(user.id(), after);
        if (after.isLocked() && !before.isLocked()) {
            audit.recordLockoutSet(user.id());
            sessions.revokeAllAfterCommit(
                    user.id(), SessionRevocationCause.FAILURE_RUN_LOCKOUT, null);
        }
    }
}
