package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditLoginStateAttribute;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * events — see {@code /docs/adr/0001-count-attempts-and-complete-every-login-on-the-login-path.md}. Enforcement is not
 * here: {@link LoginIdentityService} reports a locked identity to Spring Security, which rejects it
 * whatever the password, once it has compared that password as it would a wrong one.
 *
 * <p>Each method is its own transaction. What an attempt does to the login state — the failure
 * run, the Lockout, the dormancy basis — is not decided here: each method reports the event to
 * {@link LoginStateService}, which decides the new state, writes it through the SCIM User port's
 * narrow operations and, on a newly imposed Lockout, audits it and schedules the revocation of
 * every Session. It also serializes each of those writes itself, under the User's lock, so the
 * User this finds — by name, before any lock — is only the subject it reports, never the state
 * the module decides from. Each path here then records only its own events. Every revocation — the
 * lockout's, and an accepted login's one-session-per-User sweep — goes through
 * {@link SessionRevocationService}, which audits and logs it under its cause.
 *
 * <p>This is also where the login path's audit events are recorded, for the same reason the counting
 * is triggered here: this class already holds the identity the attempt was made against, so each
 * path names its subject without a second read.
 */
@Service
public class LoginAttemptService {

    private final ScimUserRepository users;
    private final LoginStateService loginState;
    private final SessionRevocationService sessions;
    private final AuditTrail audit;

    public LoginAttemptService(
            ScimUserRepository users,
            LoginStateService loginState,
            SessionRevocationService sessions,
            AuditTrail audit) {
        this.users = users;
        this.loginState = loginState;
        this.sessions = sessions;
        this.audit = audit;
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
     * @return the reason the {@code LOGIN_FAILURE} was recorded under: {@code reason} as
     *     {@link PasswordRefusalReason#settled} settles it — {@code UNKNOWN_ACCOUNT} when the
     *     name matched no User
     */
    @Transactional
    public PasswordRefusalReason recordFailure(String username, PasswordRefusalReason reason) {
        Optional<ScimUser> found = find(username);
        PasswordRefusalReason recorded = reason.settled(found.isPresent());
        Set<AuditLoginStateAttribute> moved = found
                .map(loginState::failureCounted)
                .map(LoginStateChange::moved)
                .orElse(Set.of());
        audit.append(new Audited.LoginFailure(found.map(ScimUser::id).orElse(null),
                recorded.audited(), AuditLoginMethod.PASSWORD, moved));
        return recorded;
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
     * <p>The User is named by the stable id its authentication carries ({@link LoginPrincipal}),
     * never looked up again by name: a directory rename landing after authentication cannot move
     * the success, the cleared run or the sweep onto whichever User holds the name by then. A User
     * gone by then fails the Login closed — nothing is written, and no {@code LOGIN_SUCCESS} is
     * silently skipped.
     *
     * <p>The authentication refused a locked or deactivated User on a read taken before the
     * User's lock, so a Lockout or a deactivation committed while the password was being compared
     * reaches here. The login-state module re-reads the standing under the lock and refuses such a
     * Login ({@link LoginStateService#credentialVerified}); then nothing at all is written here —
     * no cleared run, no {@code LOGIN_SUCCESS}, no sweep — and the refusal is handed back for the
     * caller to record as the refusal the authentication would have made a moment later.
     *
     * @param userId            the stable id of the User the Login authenticated
     * @param retainedSessionId the id the caller's own session is stored under, which the login
     *     continues in and so is the one session kept; {@code null} when the caller holds none
     * @return the standing the Login was refused on, or empty when it was accepted and recorded
     * @throws IllegalStateException if no User has {@code userId}
     */
    @Transactional
    public Optional<LoginStateService.StandingRefusal> recordPasswordSuccess(
            UUID userId, String retainedSessionId) {
        return recordSuccess(userId, retainedSessionId, LoginStateService.LoginKind.PASSWORD,
                user -> audit.append(new Audited.LoginSuccess(
                        user.id(), AuditLoginMethod.PASSWORD)));
    }

    /**
     * {@link #recordPasswordSuccess} for an Epic Login, recorded as a password Login's — failure
     * run cleared, dormancy basis moved, {@code LOGIN_SUCCESS} fail-closed, other sessions revoked
     * after commit — and differing in the method its {@code LOGIN_SUCCESS} names (D15), the MFA
     * factor it carries (D17), and in moving the dormancy basis even while the User's
     * change-required flag is set. An Epic Login is never confined by the flag
     * ({@link LoginIdentityService#loadEpicLinkedUser}), so it is real use of the account. The flag
     * itself is left set: only a successful password change clears it. Refused on the standing
     * found under the User's lock exactly as a password Login's success is.
     *
     * @param mfaFactor the MFA factor the Epic Login was made with
     * @return the standing the Login was refused on, or empty when it was accepted and recorded
     */
    @Transactional
    public Optional<LoginStateService.StandingRefusal> recordEpicSuccess(
            UUID userId, String retainedSessionId, AuditMfaFactor mfaFactor) {
        Objects.requireNonNull(mfaFactor, "an Epic Login always carries an MFA factor (D17)");
        return recordSuccess(userId, retainedSessionId, LoginStateService.LoginKind.EPIC,
                user -> audit.append(new Audited.LoginSuccess(
                        user.id(), AuditLoginMethod.SSO, mfaFactor)));
    }

    /**
     * @param kind the kind of Login, which {@link LoginStateService} reads to decide whether it is
     *     confined and so whether it moves the dormancy basis
     */
    private Optional<LoginStateService.StandingRefusal> recordSuccess(UUID userId,
            String retainedSessionId, LoginStateService.LoginKind kind,
            Consumer<ScimUser> recordLoginSuccess) {
        ScimUser user = users.findById(userId).orElseThrow(() ->
                new IllegalStateException("the authenticated User no longer exists"));
        LoginStateService.Verification verified = loginState.credentialVerified(user, kind);
        if (verified.refused()) {
            return Optional.of(verified.refusal());
        }
        recordLoginSuccess.accept(user);
        sessions.revokeOtherSessionsAfterCommit(user.id(), retainedSessionId);
        return Optional.empty();
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
        LoginStateChange counted = loginState.failureCounted(user);
        audit.append(new Audited.PasswordChangeRefused(
                user.id(), AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD, counted.moved()));
    }

    /**
     * The identity behind a submitted name, looked up on the normalized form uniqueness is decided
     * on.
     *
     * <p>A name that can name no User — a blank one, which reaches here only if the web adapter's
     * validation was bypassed, or one with whitespace at an edge — is nobody rather than an error,
     * decided by the same rule the authentication's own lookup used
     * ({@link LoginIdentityService#lookupKeyOf}) and before any lookup. So a failure for
     * {@code ada } is never counted against {@code ada}, even on an engine that would match them.
     */
    private Optional<ScimUser> find(String username) {
        return LoginIdentityService.lookupKeyOf(username).flatMap(users::findByNormalizedUserName);
    }
}
