package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginStateAttribute;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 *       rejected Login, through {@link LoginStateService#failureCounted(ScimUser)} and in this
 *       transaction, so at the threshold the User locks and every session it holds ends.
 *   <li>The new password must satisfy {@link PasswordPolicy} and must not match the current
 *       password or one of the retained previous ones — decided by {@link PasswordAcceptance}, the
 *       same owner SCIM writes use. A refusal names the rule, never a value.
 *   <li>Accepted: reported to {@link LoginStateService#passwordChanged}, which clears the failure
 *       run, stores the hash acceptance produced and clears the flag (the version advances), and
 *       moves the dormancy basis; then acceptance remembers the new hash, a
 *       {@code PASSWORD_CHANGE} event is committed with the write, and every session of the User —
 *       the one that submitted this included — ends after the commit.
 * </ol>
 *
 * <h2>Why a refusal commits</h2>
 *
 * <p>The refusals must NOT roll back: a wrong current password's count has to commit for the
 * lockout to mean anything. So the use case is one transaction that the two refusal exceptions do
 * not roll back, and the User is read under its resource lock first — taken through the SCIM
 * write lock module ({@link ScimWriteLocks}), the change advancing that User's version alone — so a
 * concurrent change or lockout is decided against the state it left rather than a stale read.
 */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final ScimUserRepository users;
    private final ScimWriteLocks writeLocks;
    private final PasswordAcceptance passwordAcceptance;
    private final PasswordEncoder passwordEncoder;
    private final LoginStateService loginState;
    private final SessionRevocationService sessions;
    private final AuditTrail audit;

    /**
     * @param passwordAcceptance decides on, encodes and remembers the new password
     * @param passwordEncoder    verifies the current password; this flow's own concern
     * @param loginState         decides and writes what the accepted change does to the login
     *                           state, and says when the change was completed
     */
    public PasswordChangeService(
            ScimUserRepository users,
            ScimWriteLocks writeLocks,
            PasswordAcceptance passwordAcceptance,
            PasswordEncoder passwordEncoder,
            LoginStateService loginState,
            SessionRevocationService sessions,
            AuditTrail audit) {
        this.users = users;
        this.writeLocks = writeLocks;
        this.passwordAcceptance = passwordAcceptance;
        this.passwordEncoder = passwordEncoder;
        this.loginState = loginState;
        this.sessions = sessions;
        this.audit = audit;
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
        // Not for the login-state writes, which LoginStateService serializes itself: this service
        // decides on its own read (refuseStanding, the current password), so the lock comes first
        // to keep that read from going stale before the module's write.
        writeLocks.lock(List.of(userId));
        ScimUser user = users.findById(userId)
                .orElseThrow(CurrentPasswordRejectedException::new);
        refuseStanding(user);
        if (!user.login().hasPassword()
                || !passwordEncoder.matches(currentPassword, user.login().passwordHash())) {
            refuseCurrentPassword(user);
            throw new CurrentPasswordRejectedException();
        }
        PasswordAcceptance.Accepted accepted =
                switch (passwordAcceptance.acceptFor(user, newPassword, user.profile().userName())) {
                    case PasswordAcceptance.Accepted password -> password;
                    case PasswordAcceptance.Refused refused -> throw refusePolicy(user, refused.rule());
                };

        // The run cleared, the change completed and the dormancy basis moved are the login-state
        // module's to decide and write; a User gone by the write is refused like a stale read.
        LoginStateChange changed = loginState.passwordChanged(user, accepted.passwordHash());
        if (!changed.completedPasswordChange()) {
            throw new CurrentPasswordRejectedException();
        }
        passwordAcceptance.remember(userId, accepted, changed.completedAt());
        audit.append(new Audited.PasswordChanged(userId, changed.moved()));
        sessions.revokeAllAfterCommit(userId, SessionRevocationCause.PASSWORD_CHANGED, null);
        LogEvent.success(log, Operation.PASSWORD_CHANGE, Category.PROCESS, Type.USER, Type.CHANGE)
                .log();
    }

    /**
     * Refuses a locked or inactive User before anything is compared, on the standing the
     * login-state module decides in its one order, so the change records the same reason a
     * Login does for the same User.
     */
    private void refuseStanding(ScimUser user) {
        Optional<LoginStateService.StandingRefusal> standing =
                LoginStateService.StandingRefusal.of(user.login(), user.profile().active());
        if (standing.isPresent()) {
            AuditPasswordChangeRefusal reason = standing.get().passwordChangeReason();
            audit.append(new Audited.PasswordChangeRefused(user.id(), reason));
            refused(reason);
            throw new CurrentPasswordRejectedException();
        }
    }

    /**
     * Counts a current password that did not verify, exactly as a refused Login is counted — the
     * same failure run, the same threshold, the same Lockout and the same Session revocation on
     * imposing it — so the change is not a second guessing surface beside Login. The login-state
     * module decides the run as it does for a Login ({@link LoginStateService#failureCounted}):
     * the Bootstrap Admin's is counted and never locks, and a User with no password is recorded
     * but counted nothing, so a User who signs in only through Epic can never be locked out from
     * here. Counted under the lock this change already holds, in its transaction, which the
     * refusal does not roll back.
     *
     * <p>Recorded as a refused {@code PASSWORD_CHANGE} rather than a {@code LOGIN_FAILURE}, naming
     * what counting it moved and after a Lockout it imposed: the attempt was made from inside an
     * authenticated session, and a reader separating the two needs the operation to do it.
     * Fail-open, as every refusal on this path is.
     */
    private void refuseCurrentPassword(ScimUser user) {
        Set<AuditLoginStateAttribute> moved = loginState.failureCounted(user).moved();
        audit.append(new Audited.PasswordChangeRefused(
                user.id(), AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD, moved));
        refused(AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD);
    }

    /** Records and logs a policy refusal, and returns what the caller throws for it. */
    private PasswordPolicyViolationException refusePolicy(ScimUser user, PasswordPolicy.Rule rule) {
        AuditPasswordChangeRefusal reason = switch (rule) {
            case TOO_SHORT -> AuditPasswordChangeRefusal.TOO_SHORT;
            case TOO_LONG -> AuditPasswordChangeRefusal.TOO_LONG;
            case CONTAINS_USER_NAME -> AuditPasswordChangeRefusal.CONTAINS_USER_NAME;
            case REUSED -> AuditPasswordChangeRefusal.REUSED;
        };
        audit.append(new Audited.PasswordChangeRefused(user.id(), reason));
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
