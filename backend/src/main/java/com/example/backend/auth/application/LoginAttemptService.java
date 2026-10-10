package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
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
 * whatever the password, once it has compared that password as it would a wrong one.
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
 * audits it and schedules the revocation; each path then records only its own refusal. Every
 * revocation here — the lockout's, and an accepted login's one-session-per-User sweep — goes
 * through {@link SessionRevocationService}, which audits and logs it under its cause.
 *
 * <p>This is also where the login path's audit events are recorded, for the same reason the counting
 * is here: this class already holds the identity the attempt was made against, so each path names
 * its subject without a second read. Telling a lockout being imposed from one already in force is
 * {@link FailureCounter}'s, which holds the login state before and after the counted failure.
 */
@Service
public class LoginAttemptService {

    private final ScimUserRepository users;
    private final SessionRevocationService sessions;
    private final AuditTrail audit;
    private final Clock clock;
    private final FailureCounter failures;

    public LoginAttemptService(
            ScimUserRepository users,
            SessionRevocationService sessions,
            LockoutPolicy policy,
            AuditTrail audit,
            Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
        this.failures = new FailureCounter(users, sessions, policy, audit, clock);
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
     *
     * @return the reason the {@code LOGIN_FAILURE} was recorded under: {@code reason}, or
     *     {@code UNKNOWN_ACCOUNT} when the name matched no User
     */
    @Transactional
    public AuditRefusalReason recordFailure(String username, AuditRefusalReason reason) {
        Optional<ScimUser> found = find(username);
        if (found.isEmpty()) {
            audit.recordLoginFailure(
                    null, AuditRefusalReason.UNKNOWN_ACCOUNT, AuditLoginMethod.PASSWORD);
            return AuditRefusalReason.UNKNOWN_ACCOUNT;
        }

        ScimUser user = found.get();
        failures.count(user);
        audit.recordLoginFailure(user.id(), reason, AuditLoginMethod.PASSWORD);
        return reason;
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
    public void recordPasswordSuccess(String username, String retainedSessionId) {
        recordSuccess(username, retainedSessionId, Confinement.BY_CHANGE_REQUIRED_FLAG,
                user -> audit.recordLoginSuccess(user.id(), AuditLoginMethod.PASSWORD));
    }

    /**
     * {@link #recordPasswordSuccess} for an Epic Login, recorded as a password Login's — failure
     * run cleared, dormancy basis moved, {@code LOGIN_SUCCESS} fail-closed, other sessions revoked
     * after commit — and differing in the method its {@code LOGIN_SUCCESS} names (D15), the MFA
     * factor it carries (D17), and in moving the dormancy basis even while the User's
     * change-required flag is set. An Epic Login is never confined by the flag
     * ({@link LoginIdentityService#loadEpicLinkedUser}), so it is real use of the account. The flag
     * itself is left set: only a successful password change clears it.
     *
     * @param mfaFactor the MFA factor the Epic Login was made with
     */
    @Transactional
    public void recordEpicSuccess(
            String username, String retainedSessionId, AuditMfaFactor mfaFactor) {
        Objects.requireNonNull(mfaFactor, "an Epic Login always carries an MFA factor (D17)");
        recordSuccess(username, retainedSessionId, Confinement.NONE,
                user -> audit.recordLoginSuccess(user.id(), AuditLoginMethod.SSO, mfaFactor));
    }

    /**
     * @param confinement whether the Login's session is confined while the change-required flag is
     *     set — a password Login's is, an Epic Login's is not
     */
    private void recordSuccess(String username, String retainedSessionId, Confinement confinement,
            Consumer<ScimUser> recordLoginSuccess) {
        find(username).ifPresent(user -> {
            ScimLoginState cleared = user.login().withFailureRunCleared();
            if (cleared != user.login()) {
                users.updateLoginState(user.id(), cleared);
            }
            // A login moves the dormancy basis, the one thing the dormancy job measures from —
            // unless its session is confined because the User still owes a required password
            // change. A confined session can do nothing but change the password or log out, so it
            // is not use of the account, and counting it would let an imposed credential that is
            // never replaced stay live for as long as somebody keeps logging in with it (ADR
            // 0008). The change itself moves the basis instead (PasswordChangeService). Only a
            // password Login is confined: an Epic Login presented no password of ours, so it is
            // real use even while the flag is set (ADR 0008 addendum). Its own narrow write, so
            // it neither advances the version nor rewrites the failure run.
            if (!confinement.confines(user.login().isPasswordChangeRequired())) {
                users.recordAuthentication(user.id(), clock.instant());
            }
            recordLoginSuccess.accept(user);
            sessions.revokeOtherSessionsAfterCommit(user.id(), retainedSessionId);
        });
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
