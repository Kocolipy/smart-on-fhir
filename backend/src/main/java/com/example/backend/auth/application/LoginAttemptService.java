package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.domain.AccountSessions;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records how each login attempt ended, so repeated failures lock an identity and an accepted login
 * clears the run.
 *
 * <p>Its callers are {@link LoginService}, which records every attempt it makes, and
 * {@link PasswordChangeService}, whose wrong current password counts toward the same run; nothing
 * else counts attempts. The counting is explicit rather than driven by Spring Security's authentication
 * events — see {@code /docs/adr/0001-count-login-attempts-on-the-login-path.md}. Enforcement is not
 * here: {@link LoginIdentityService} reports a locked identity to Spring Security, which rejects it
 * before any password is checked.
 *
 * <p>Each method is its own transaction, and both write through the SCIM User port's narrow
 * login-state operation. Narrow matters twice over. It cannot revert a profile attribute a connector
 * or an administrator changed between this attempt starting and finishing — and it does not advance
 * the resource's version, because a failure run is not a SCIM attribute: a wrong password changes
 * nothing a connector can read, and moving the ETag would invalidate every cached copy of the User
 * on every mistyped password.
 *
 * <p>Imposing a lock also ends the identity's live sessions, because a lock that left them alone
 * would close the front door while the identity kept acting through a session it already held. The
 * revocation runs after the transaction commits, because Redis is not in the transaction and a
 * revocation already performed cannot be undone by a rollback — see
 * {@code /docs/adr/0002-revoke-sessions-after-commit.md}. Both failure paths carry that out through
 * one {@link FailureCounter}, which persists the counted failure and, on a newly imposed lock,
 * audits it and schedules the revocation; each path then records only its own refusal.
 *
 * <p>This is also where the login path's audit events are recorded, for the same reason the counting
 * is here: this class already holds the identity the attempt was made against, so each path names
 * its subject without a second read. Telling a lockout being imposed from one already in force is
 * {@link FailureCounter}'s, which holds the login state before and after the counted failure.
 */
@Service
public class LoginAttemptService {

    private final ScimUserRepository users;
    private final AccountSessions sessions;
    private final AfterCommit afterCommit;
    private final AuditTrail audit;
    private final Clock clock;
    private final FailureCounter failures;

    public LoginAttemptService(
            ScimUserRepository users,
            AccountSessions sessions,
            AfterCommit afterCommit,
            LockoutPolicy policy,
            AuditTrail audit,
            Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.afterCommit = afterCommit;
        this.audit = audit;
        this.clock = clock;
        this.failures = new FailureCounter(users, sessions, afterCommit, policy, audit, clock);
    }

    /**
     * Counts a rejected attempt against {@code username}, locking the identity once the policy's
     * limit is reached — and never locking the Bootstrap Admin, whose failures are counted and
     * audited like anyone's but cannot close the deployment's last way in.
     *
     * <p>The exemption is read off the User's own reservation marker rather than by comparing its
     * name to a configured string. That is the substantive change from the account aggregate's
     * version of this method: the identity that must never lock is recognised by what it IS, so a
     * rename cannot move the exemption and a second identity cannot acquire it by taking the
     * configured name.
     *
     * <p>An unknown username is ignored rather than recorded. Nothing is created for it, so a caller
     * cannot learn from timing or from stored state whether the name exists. It still produces a
     * {@code LOGIN_FAILURE} event — with no subject, because there is no identity to name and the
     * submitted username is the one thing that must not be recorded in its place. An administrator
     * reading a run of subject-less failures is seeing attempts against names that do not exist,
     * which is exactly the distinction worth having.
     *
     * <p>Every append on this path is fail-open: the caller is already receiving a bare {@code 401}
     * and a trail that cannot be written must not change that answer. See {@link AuditTrail}.
     */
    @Transactional
    public void recordFailure(String username, AuditRefusalReason reason) {
        Optional<ScimUser> found = find(username);
        if (found.isEmpty()) {
            audit.recordLoginFailure(
                    null, AuditRefusalReason.UNKNOWN_ACCOUNT, AuditLoginMethod.PASSWORD);
            return;
        }

        ScimUser user = found.get();
        failures.count(user);
        audit.recordLoginFailure(user.id(), reason, AuditLoginMethod.PASSWORD);
    }

    /**
     * Records a refused Login that counts toward no failure run: an Epic Login the login decision
     * refused (D12). Epic checked the credential, not this service, so a refusal here is no
     * evidence of guessing — and counting it would let anyone holding an Epic session that names
     * a case variant of a User's {@code userName} lock that User out. The User's login state is
     * neither read nor written; only the {@code LOGIN_FAILURE} is recorded, fail-open like every
     * refusal on this path.
     *
     * @param subjectId stable id of the refused User, or {@code null} when the attempt named no
     *     acceptable User — which is then not recorded at all
     */
    @Transactional
    public void recordRefusal(UUID subjectId, AuditRefusalReason reason, AuditLoginMethod method) {
        audit.recordLoginRefusal(subjectId, reason, method);
    }

    /**
     * Clears the failure run of an identity that has just logged in, and records when it did.
     *
     * <p>The success event is fail-closed, unlike everything on the failure path: a session this
     * service could not account for is one it does not issue. The append joins this transaction, so
     * it takes the cleared failure run down with it if it cannot be written.
     *
     * <p>A successful login also ends every other session the identity holds — one concurrent
     * session per User, so a credential cannot be in use from two places at once and a stolen
     * session does not outlive its owner's next sign-in. The rule holds for a login confined by a
     * required change as for any other. It runs after the commit, on the path a lockout's revocation
     * takes and for the same reason ({@code /docs/adr/0002-revoke-sessions-after-commit.md}): a
     * rolled-back login must not have signed its owner out everywhere else.
     *
     * @param retainedSessionId the id the caller's own session is stored under, which the login
     *     continues in and so is the one session kept; {@code null} when the caller holds none
     */
    @Transactional
    public void recordSuccess(String username, String retainedSessionId) {
        recordSuccess(username, retainedSessionId, AuditLoginMethod.PASSWORD);
    }

    /**
     * {@link #recordSuccess(String, String)} for a Login made by {@code method}: an Epic Login's
     * success is recorded exactly as a password Login's — failure run cleared, dormancy basis
     * moved, {@code LOGIN_SUCCESS} fail-closed, other sessions revoked after commit — and differs
     * only in the method its {@code LOGIN_SUCCESS} names (D15).
     */
    @Transactional
    public void recordSuccess(
            String username, String retainedSessionId, AuditLoginMethod method) {
        find(username).ifPresent(user -> {
            ScimLoginState cleared = user.login().withFailureRunCleared();
            if (cleared != user.login()) {
                users.updateLoginState(user.id(), cleared);
            }
            // A login moves the dormancy basis, the one thing the dormancy job measures from —
            // unless the User still owes a required password change. Such a
            // session can do nothing but change the password or log out, so it is not use of the
            // account, and counting it would let an imposed credential that is never replaced
            // stay live for as long as somebody keeps logging in with it. The change itself moves
            // the basis instead (PasswordChangeService). Its own narrow write, so it neither
            // advances the version nor rewrites the failure run.
            if (!user.login().isPasswordChangeRequired()) {
                users.recordAuthentication(user.id(), clock.instant());
            }
            audit.recordLoginSuccess(user.id(), method);
            afterCommit.run(() -> sessions.revokeAllExcept(user.id(), retainedSessionId));
        });
    }

    /**
     * {@link #recordSuccess(String, String)} for a caller holding no session, every session of the
     * identity ending.
     */
    @Transactional
    public void recordSuccess(String username) {
        recordSuccess(username, null);
    }

    /**
     * Counts a self-service password change whose current password did not verify, exactly as a
     * rejected login is counted — the same failure run, the same threshold, the same lock and the
     * same session revocation on imposing it — so the change endpoint is not a second guessing
     * surface beside the login one. The Bootstrap Admin's run is counted and never locks, as on
     * the login path.
     *
     * <p>Recorded as a refused {@code PASSWORD_CHANGE} rather than a {@code LOGIN_FAILURE}: the
     * attempt was made from inside an authenticated session, and a reader separating the two needs
     * the operation to do it. Fail-open, as every refusal on this path is.
     */
    @Transactional
    public void recordPasswordChangeFailure(UUID userId) {
        Optional<ScimUser> found = users.findById(userId);
        if (found.isEmpty()) {
            return;
        }
        ScimUser user = found.get();
        failures.count(user);
        audit.recordPasswordChangeRefused(
                user.id(), AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD);
    }

    /**
     * The identity behind a submitted name, looked up on the normalized form uniqueness is decided
     * on.
     *
     * <p>A blank submission is nobody rather than an error: it reaches here only if the web adapter's
     * validation was bypassed, and the honest answer is the same one an unknown name gets.
     */
    private Optional<ScimUser> find(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return users.findByNormalizedUserName(NormalizedUserName.of(username));
    }
}
