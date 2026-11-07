package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.auth.application.LoginStateService.PasswordChangeVerdict;
import com.example.backend.auth.application.LoginStateService.VerifiedPassword;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authenticated self-service password change: the one thing a User with a required change may
 * do besides logging out, and available to every other User too.
 *
 * <h2>Compare first, then confirm under the lock</h2>
 *
 * <p>Every Argon2id operation the change needs runs first, in no transaction, on the User read
 * without its lock: the comparison of the current password with the stored credential and — once
 * that verified — {@link PasswordAcceptance}'s decision on the new one, which compares it with the
 * current credential and the retained history and encodes it. Each is slow by design, and a
 * transaction open across them would hold a pooled connection, and the User's row lock, for
 * their whole length: a concurrent password Login or SCIM write to the User would wait on them.
 * So, as a password Login's comparison does (ADR 0001), they run before any transaction opens.
 *
 * <p>Then one transaction, which this opens itself, confirms the change under the User's lock,
 * through the login-state module ({@link LoginStateService#passwordChange}, or
 * {@link LoginStateService#passwordChangeRefused} for a current password that did not verify),
 * which decides on the state read there, in this order, and records the {@code PASSWORD_CHANGE}:
 *
 * <ol>
 *   <li>A locked or inactive User is refused, and nothing is counted — the Lockout blocks this
 *       path exactly as it blocks Login, and only an Admin's Unlock lifts it.
 *   <li>The current password must be the stored one. A wrong one — or one the credential was
 *       replaced from since the comparison — is counted toward the same failure run as a
 *       rejected Login, so at the threshold the User locks and every session it holds ends.
 *   <li>The new password must satisfy {@link PasswordPolicy} and must not match the current
 *       password or one of the retained previous ones — decided by {@link PasswordAcceptance}, the
 *       same owner SCIM writes use. A refusal names the rule, never a value.
 *   <li>Accepted: the failure run is cleared, the hash acceptance produced stored and the flag
 *       cleared (the version advances), and the dormancy basis moved; its {@code PASSWORD_CHANGE}
 *       is recorded with the write and every session of the User — the one that submitted this
 *       included — ends after the commit. Then acceptance remembers the new hash, in the same
 *       transaction, as of the instant the change was completed at.
 * </ol>
 *
 * <p>The verdict comes back once the transaction has committed, and only then is a refusal
 * thrown: a wrong current password's count has to commit for the Lockout to mean anything, so no
 * refusal rolls anything back. The transaction is not retried on a deadlock: like a Login, the
 * change is neither a SCIM nor an administrative write, and the User submits it again.
 */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final ScimUserRepository users;
    private final PasswordAcceptance passwordAcceptance;
    private final PasswordEncoder passwordEncoder;
    private final LoginStateService loginState;

    /**
     * The change's one transaction, once the comparisons have answered: the template's default
     * propagation, {@code PROPAGATION_REQUIRED}, which opens it, since {@link #changePassword}
     * runs in none.
     */
    private final TransactionTemplate confirmation;

    /**
     * @param passwordAcceptance decides on, encodes and remembers the new password
     * @param passwordEncoder    verifies the current password; this flow's own concern
     * @param loginState         confirms the change under the User's lock, writes what it does to
     *                           the login state, records it, and says when it was completed
     * @param transactionManager the primary transaction manager, which commits the confirmation,
     *                           its records and the remembered password together
     */
    public PasswordChangeService(
            ScimUserRepository users,
            PasswordAcceptance passwordAcceptance,
            PasswordEncoder passwordEncoder,
            LoginStateService loginState,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.passwordAcceptance = passwordAcceptance;
        this.passwordEncoder = passwordEncoder;
        this.loginState = loginState;
        this.confirmation = new TransactionTemplate(transactionManager);
    }

    /**
     * Replaces the User's password with {@code newPassword}, given its current one.
     *
     * @param userId the caller's stable id, taken from its session — never from the request
     * @throws CurrentPasswordRejectedException when the User is gone, inactive or locked, or the
     *                                          current password did not verify
     * @throws PasswordPolicyViolationException when the new password breaks a rule
     */
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        // Read without the lock, for the comparisons alone: everything that decides is read again
        // by the login-state module under the User's lock. A User gone here is refused as one gone
        // there is, unrecorded.
        ScimUser user = users.findById(userId).orElseThrow(CurrentPasswordRejectedException::new);
        PasswordChangeVerdict verdict;
        if (user.login().hasPassword()
                && passwordEncoder.matches(currentPassword, user.login().passwordHash())) {
            VerifiedPassword current = VerifiedPassword.of(user.login().passwordHash());
            PasswordAcceptance.Decision decided =
                    passwordAcceptance.acceptFor(user, newPassword, user.profile().userName());
            verdict = confirmation.execute(status -> confirmed(userId, current, decided))
                    .orElseThrow(CurrentPasswordRejectedException::new);
        } else {
            // A User with no credential is refused as a wrong current password, by the module,
            // which counts nothing for it: there is no password to guess.
            verdict = confirmation.execute(status -> loginState.passwordChangeRefused(userId))
                    .orElseThrow(CurrentPasswordRejectedException::new);
        }
        switch (verdict) {
            case PasswordChangeVerdict.Changed changed ->
                    LogEvent.success(log, Operation.PASSWORD_CHANGE, Category.PROCESS, Type.USER,
                            Type.CHANGE).log();
            case PasswordChangeVerdict.Refused refused -> {
                refused(refused.reason());
                throw new CurrentPasswordRejectedException();
            }
            case PasswordChangeVerdict.RuleBroken broken -> {
                refused(broken.reason());
                throw new PasswordPolicyViolationException(broken.rule());
            }
        }
    }

    /**
     * The change confirmed under the User's lock, in its one transaction, and — when it completed
     * — the new hash remembered as of the instant it was completed at, in the same transaction, so
     * a rollback takes the history entry with it.
     */
    private Optional<PasswordChangeVerdict> confirmed(
            UUID userId, VerifiedPassword current, PasswordAcceptance.Decision decided) {
        return loginState.passwordChange(userId, current, decided).map(verdict -> {
            if (verdict instanceof PasswordChangeVerdict.Changed changed) {
                passwordAcceptance.remember(
                        userId, changed.credential(), changed.change().completedAt());
            }
            return verdict;
        });
    }

    /** Logs a refusal by its closed-set reason; no identity and no value is written. */
    private static void refused(AuditPasswordChangeRefusal reason) {
        LogEvent.refused(log, Operation.PASSWORD_CHANGE, Category.PROCESS, Type.USER, Type.DENIED)
                .addKeyValue(LogEvent.REASON, reason.name())
                .log();
    }
}
