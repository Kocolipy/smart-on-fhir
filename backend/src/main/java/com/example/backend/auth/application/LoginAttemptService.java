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
     * Counts a refused password Login against the User the authentication's one read resolved,
     * and records its {@code LOGIN_FAILURE} — whether the authentication refused it or the login
     * decision under the User's lock did. What the attempt does to the failure run is the
     * login-state module's ({@link LoginStateService#failureCounted(ScimUser,
     * PasswordRefusalReason)}), handed the refusal's reason: the run lengthens, a Lockout is
     * imposed at the policy's limit — never on the Bootstrap Admin, recognised by its reservation
     * marker — and a User already locked, or with no password to guess, is counted nothing. A
     * Login refused for a Lockout ({@code ACCOUNT_LOCKED}) is counted nothing on the refusal's
     * decision, not the state the module re-reads: an Admin Unlock committed between the refusal
     * and its count does not turn the attempt into the first failure of the run the Unlock
     * started.
     *
     * <p>The User is named by its stable id, never looked up again by the submitted name: a
     * directory rename landing after the authentication's read cannot move the failure, a Lockout
     * or its revocation onto whichever User holds the name by then.
     *
     * <p>A name the read found no User for is recorded rather than counted. Nothing is created for
     * it, so a caller cannot learn from stored state whether the name exists. It still produces a
     * {@code LOGIN_FAILURE} event — with no subject, because there is no identity to name and the
     * submitted username is the one thing that must not be recorded in its place. An administrator
     * reading a run of subject-less failures is seeing attempts against names that do not exist,
     * which is exactly the distinction worth having. A User gone by the time this runs is recorded
     * the same way.
     *
     * <p>Every append on this path is fail-open: the caller is already receiving a bare {@code 401}
     * and a trail that cannot be written must not change that answer. See {@link AuditTrail}.
     *
     * @param userId the stable id of the User the authentication's read resolved, or {@code null}
     *     when it found none
     * @return the reason the {@code LOGIN_FAILURE} was recorded under: {@code reason} as
     *     {@link PasswordRefusalReason#settled} settles it — {@code UNKNOWN_ACCOUNT} when no User
     *     was resolved, or none has that id any longer
     */
    @Transactional
    public PasswordRefusalReason recordFailure(UUID userId, PasswordRefusalReason reason) {
        Optional<ScimUser> found = Optional.ofNullable(userId).flatMap(users::findById);
        PasswordRefusalReason recorded = reason.settled(found.isPresent());
        Set<AuditLoginStateAttribute> moved = found
                .map(user -> loginState.failureCounted(user, recorded))
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
     * gone by then — deleted while the password was being compared — is refused exactly as one
     * gone under its lock is ({@link LoginStateService.Verification#userGone}), with nothing
     * written: no cleared run, no {@code LOGIN_SUCCESS}, no sweep. The caller records it through
     * the failure path, which finds no User and records it against nobody.
     *
     * <p>The authentication refused a locked or deactivated User on a read taken before the
     * User's lock, and compared the password against the credential that read found, so a
     * Lockout, a deactivation or a replaced password committed while the password was being
     * compared reaches here. The login-state module decides the Login once more under the lock
     * ({@link LoginStateService#credentialVerified}) and refuses such a Login; then nothing at all
     * is written here — no cleared run, no {@code LOGIN_SUCCESS}, no sweep — and the refusal is
     * handed back for the caller to record: as the refusal the authentication would have made a
     * moment later, or, for a replaced credential, as the wrong password it now is.
     *
     * @param userId            the stable id of the User the Login authenticated
     * @param credential        the stored credential the authentication compared the password
     *     against, which the module confirms is still the stored one
     * @param retainedSessionId the id the caller's own session is stored under, which the login
     *     continues in and so is the one session kept; {@code null} when the caller holds none
     * @return the decision: why the Login was refused, or that it was accepted and recorded, and
     *     whether its session is confined
     */
    @Transactional
    public LoginStateService.Verification recordPasswordSuccess(UUID userId,
            LoginStateService.VerifiedCredential credential, String retainedSessionId) {
        return recordSuccess(userId, retainedSessionId, credential,
                user -> audit.append(new Audited.LoginSuccess(
                        user.id(), AuditLoginMethod.PASSWORD)));
    }

    /**
     * {@link #recordPasswordSuccess} for an Epic Login, recorded as a password Login's — failure
     * run cleared, dormancy basis moved, {@code LOGIN_SUCCESS} fail-closed, other sessions revoked
     * after commit — and differing in the method its {@code LOGIN_SUCCESS} names (D15), the MFA
     * factor it carries (D17), and in moving the dormancy basis even while the User's
     * change-required flag is set. An Epic Login is never confined by the flag, so it is real use
     * of the account. The flag itself is left set: only a successful password change clears it.
     * Refused on the standing found under the User's lock exactly as a password Login's success
     * is, and so is a User gone before the lock — deleted since the Login read it — with nothing
     * written; it presented no credential of ours, so none can have been replaced.
     *
     * @param mfaFactor the MFA factor the Epic Login was made with
     * @return the decision: the standing the Login was refused on, or that it was accepted and
     *     recorded, never confined
     */
    @Transactional
    public LoginStateService.Verification recordEpicSuccess(
            UUID userId, String retainedSessionId, AuditMfaFactor mfaFactor) {
        Objects.requireNonNull(mfaFactor, "an Epic Login always carries an MFA factor (D17)");
        return recordSuccess(userId, retainedSessionId, LoginStateService.VerifiedCredential.epic(),
                user -> audit.append(new Audited.LoginSuccess(
                        user.id(), AuditLoginMethod.SSO, mfaFactor)));
    }

    /**
     * The success of either login method: decided under the User's lock by the login-state module,
     * and recorded only when that decision accepts it. A User no longer there by stable id is
     * handed back as {@link LoginStateService.Verification#userGone} — the refusal one gone under
     * the lock gets — before anything is written or scheduled.
     *
     * @param credential what the authentication verified, which {@link LoginStateService} confirms
     *     under the lock and asks whether the Login is confined, and so whether it moves the
     *     dormancy basis
     */
    private LoginStateService.Verification recordSuccess(UUID userId, String retainedSessionId,
            LoginStateService.VerifiedCredential credential,
            Consumer<ScimUser> recordLoginSuccess) {
        Optional<ScimUser> found = users.findById(userId);
        if (found.isEmpty()) {
            return LoginStateService.Verification.userGone();
        }
        ScimUser user = found.get();
        LoginStateService.Verification verification =
                loginState.credentialVerified(user, credential);
        if (verification.refused()) {
            return verification;
        }
        recordLoginSuccess.accept(user);
        sessions.revokeOtherSessionsAfterCommit(user.id(), retainedSessionId);
        return verification;
    }

    /**
     * Counts a self-service password change whose current password did not verify, exactly as a
     * rejected login is counted — the same failure run, the same threshold, the same lock and the
     * same session revocation on imposing it — so the change endpoint is not a second guessing
     * surface beside the login one. The login-state module decides the run as it does on the
     * login path ({@link LoginStateService#failureCounted}): the Bootstrap Admin's is counted and
     * never locks, and a User with no password is recorded but counted nothing, so a User who signs
     * in only through Epic can never be locked out from here.
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
        Set<AuditLoginStateAttribute> moved = loginState.failureCounted(user).moved();
        audit.append(new Audited.PasswordChangeRefused(
                user.id(), AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD, moved));
    }
}
