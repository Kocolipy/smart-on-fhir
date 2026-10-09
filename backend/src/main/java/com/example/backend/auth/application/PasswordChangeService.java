package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimUserSessions;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authenticated self-service password change: the one thing a User with a required change may
 * do besides logging out, and available to every other User too.
 *
 * <p>In order:
 *
 * <ol>
 *   <li>An inactive or locked User is refused before anything is compared — the lockout blocks this
 *       path exactly as it blocks Login, and only an Admin's Unlock lifts it.
 *   <li>The current password must verify. A wrong one is counted toward the same failure run as a
 *       rejected Login ({@link LoginAttemptService#recordPasswordChangeFailure}), so at the
 *       threshold the User locks and every session it holds ends.
 *   <li>The new password must satisfy {@link PasswordPolicy} and must not match the current
 *       password or one of the retained previous ones — decided by {@link PasswordAcceptance}, the
 *       same owner SCIM writes use. A refusal names the rule, never a value.
 *   <li>Accepted: the new password is stored as the hash acceptance produced, the flag is cleared,
 *       the version advances, acceptance remembers the new hash, a {@code PASSWORD_CHANGE} event is
 *       committed with the write,
 *       and every session of the User — the one that submitted this included — ends after the
 *       commit.
 * </ol>
 *
 * <h2>Why a refusal commits</h2>
 *
 * <p>The refusals must NOT roll back: a wrong current password's count has to commit for the
 * lockout to mean anything. So the use case is one transaction that the two refusal exceptions do
 * not roll back, and the User is read under its resource lock first, so a concurrent change or
 * lockout is decided against the state it left rather than a stale read.
 */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final ScimUserRepository users;
    private final PasswordAcceptance passwordAcceptance;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService attempts;
    private final ScimUserSessions sessions;
    private final AuditTrail audit;
    private final Clock clock;

    /**
     * @param passwordAcceptance decides on, encodes and remembers the new password
     * @param passwordEncoder    verifies the current password; this flow's own concern
     */
    public PasswordChangeService(
            ScimUserRepository users,
            PasswordAcceptance passwordAcceptance,
            PasswordEncoder passwordEncoder,
            LoginAttemptService attempts,
            ScimUserSessions sessions,
            AuditTrail audit,
            Clock clock) {
        this.users = users;
        this.passwordAcceptance = passwordAcceptance;
        this.passwordEncoder = passwordEncoder;
        this.attempts = attempts;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Replaces the User's password with {@code newPassword}, given its current one.
     *
     * @param userId the caller's stable id, taken from its session — never from the request
     * @throws CurrentPasswordRejectedException when the User is gone, inactive or locked, or the
     *                                          current password did not verify
     * @throws PasswordPolicyViolationException when the new password breaks a rule
     */
    @Transactional(noRollbackFor = {
            CurrentPasswordRejectedException.class, PasswordPolicyViolationException.class})
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        ScimUser user = users.findByIdForUpdate(userId)
                .orElseThrow(CurrentPasswordRejectedException::new);
        refuseStanding(user);
        if (!user.login().hasPassword()
                || !passwordEncoder.matches(currentPassword, user.login().passwordHash())) {
            attempts.recordPasswordChangeFailure(user.id());
            refused(AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD);
            throw new CurrentPasswordRejectedException();
        }
        PasswordAcceptance.Accepted accepted =
                switch (passwordAcceptance.acceptFor(user, newPassword, user.profile().userName())) {
                    case PasswordAcceptance.Accepted password -> password;
                    case PasswordAcceptance.Refused refused -> throw refusePolicy(user, refused.rule());
                };

        Instant now = clock.instant();
        String passwordHash = accepted.passwordHash();
        // The current password verified, which ends a failure run as an accepted login does.
        ScimLoginState cleared = user.login().withFailureRunCleared();
        if (cleared != user.login()) {
            users.updateLoginState(userId, cleared);
        }
        users.completePasswordChange(userId, passwordHash, now)
                .orElseThrow(CurrentPasswordRejectedException::new);
        // The current password verified, so this is use of the account, and for a User that
        // owed the change it is the first: its confined logins did not move the dormancy basis
        // (LoginAttemptService#recordPasswordSuccess), so the change does.
        users.recordAuthentication(userId, now);
        passwordAcceptance.remember(userId, accepted, now);
        audit.recordPasswordChanged(userId);
        sessions.revokeAfterCommit(
                null, userId, EnumSet.of(ScimUserSessions.Cause.PASSWORD_CHANGED));
        LogEvent.success(log, Operation.PASSWORD_CHANGE, Category.PROCESS, Type.USER, Type.CHANGE)
                .log();
    }

    /** Refuses an inactive or locked User before anything is compared. */
    private void refuseStanding(ScimUser user) {
        if (!user.profile().active()) {
            audit.recordPasswordChangeRefused(user.id(), AuditPasswordChangeRefusal.ACCOUNT_DISABLED);
            refused(AuditPasswordChangeRefusal.ACCOUNT_DISABLED);
            throw new CurrentPasswordRejectedException();
        }
        if (user.login().isLocked()) {
            audit.recordPasswordChangeRefused(user.id(), AuditPasswordChangeRefusal.ACCOUNT_LOCKED);
            refused(AuditPasswordChangeRefusal.ACCOUNT_LOCKED);
            throw new CurrentPasswordRejectedException();
        }
    }

    /** Records and logs a policy refusal, and returns what the caller throws for it. */
    private PasswordPolicyViolationException refusePolicy(ScimUser user, PasswordPolicy.Rule rule) {
        AuditPasswordChangeRefusal reason = switch (rule) {
            case TOO_SHORT -> AuditPasswordChangeRefusal.TOO_SHORT;
            case TOO_LONG -> AuditPasswordChangeRefusal.TOO_LONG;
            case CONTAINS_USER_NAME -> AuditPasswordChangeRefusal.CONTAINS_USER_NAME;
            case REUSED -> AuditPasswordChangeRefusal.REUSED;
        };
        audit.recordPasswordChangeRefused(user.id(), reason);
        refused(reason);
        return new PasswordPolicyViolationException(rule);
    }

    /** Logs a refusal by its closed-set reason; no identity and no value is written. */
    private static void refused(AuditPasswordChangeRefusal reason) {
        LogEvent.refused(log, Operation.PASSWORD_CHANGE, Category.PROCESS, Type.USER, Type.DENIED)
                .addKeyValue(LogEvent.REASON, reason.name())
                .log();
    }
}
