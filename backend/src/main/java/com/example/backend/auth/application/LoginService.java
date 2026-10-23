package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.application.LoginOutcome.EpicRefused;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logging in: submitted credentials become either an authentication or a
 * refusal, and the attempt is counted against the account either way.
 *
 * <p>The counting is here rather than at the call site because it is part of
 * what logging in <em>is</em> — an entry point that authenticated credentials
 * without it would silently have no lockout. A caller therefore cannot obtain an
 * authentication from submitted credentials and skip the failure run; it gets
 * both or neither.
 *
 * <p>Enforcement is deliberately elsewhere: {@link LoginIdentityService} reports a
 * locked identity to Spring Security, which refuses it whatever the password, after
 * comparing that password exactly as it would compare a wrong one. This module
 * records what happened; the rule for what counts as locked lives in
 * {@link com.example.backend.scim.domain.ScimLoginState}.
 */
@Service
public class LoginService {

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService attempts;
    private final LoginIdentityService identities;
    private final LoginOutcomeService outcomes;

    public LoginService(
            AuthenticationManager authenticationManager,
            LoginAttemptService attempts,
            LoginIdentityService identities,
            LoginOutcomeService outcomes) {
        this.authenticationManager = authenticationManager;
        this.attempts = attempts;
        this.identities = identities;
        this.outcomes = outcomes;
    }

    /**
     * The login decision for a password Login: the submitted credentials become an accepted
     * Login or a refusal, and the attempt is counted against the account either way.
     *
     * <p>Every refusal — wrong password, unknown username, locked account, disabled account —
     * ends in the same kind of decision, a {@link PasswordRefused}, told apart only by the reason
     * the audit trail records, so the web adapter answers each with the same bare {@code 401}.
     *
     * <p>The run of failures an accepted login ends is cleared, and its fail-closed
     * {@code LOGIN_SUCCESS} written, before this returns, so a caller holding an authentication
     * is by definition one whose account was not refused, whatever it does with the
     * authentication next.
     *
     * <p>Spring Security's check of a locked or deactivated account reads the User before the
     * login-state lock, and the password comparison after it takes long enough for another
     * attempt's Lockout, or a connector's deactivation, to commit in between. The success is
     * therefore decided once more on the standing read under the lock
     * ({@link LoginAttemptService#recordPasswordSuccess}); a Login refused there ends as the
     * refusal the check would have made a moment later — {@code ACCOUNT_LOCKED} or
     * {@code ACCOUNT_DISABLED}, recorded and counted exactly as that refusal is — so it never
     * lifts the Lockout it lost the race to (ADR 0007).
     *
     * <p>A refusal is recorded here, through {@link LoginOutcomeService}, the one module that
     * records how a Login ended by either method, so no caller can refuse a Login without the
     * record or skip the failure run. An acceptance is not: the Login has not ended until the
     * caller has signed the session in, so the caller records the {@link SignedIn} it is handed
     * once it has, naming the session the User goes on to use ({@code LoginCompletion}; ADR 0001).
     * Neither record carries the submitted {@code username}. It is
     * the single most sensitive value passing through here — it is half a credential, and on a
     * failed attempt it is very often a mistyped password — so it stays out of the log, in the
     * message and in the context alike. The accepted Login's record carries the identity's stable
     * id as {@code user.id}; the refused one carries no user field at all, because the identity it
     * named is unresolved, and no reason either: it says only that the Login was refused (Logging
     * §2.2). Whether the password was wrong, the name unknown, or the account locked or
     * deactivated tells whether an account exists, so that is the audit trail's
     * {@code LOGIN_FAILURE} alone, read by an administrator, and the {@code login} counter's
     * {@code reason} tag, which names no account. A run of wrong passwords is still told from a
     * run against names that do not exist, there.
     *
     * @return how the decision ended — a {@link SignedIn} with the accepted Login, or a
     *     {@link PasswordRefused}, already recorded
     */
    public LoginDecision logIn(String username, String password) {
        return logIn(username, password, null);
    }

    /**
     * {@link #logIn(String, String)} from a caller that may already hold a session: an accepted
     * login ends every other session of the identity once the cleared failure run commits, and
     * keeps this one, which the caller goes on to rotate and sign in. A refused one names this
     * session by hash on its record, and leaves ending it to the caller, the web adapter.
     *
     * @param retainedSessionId the id the caller's session is stored under, or {@code null} when
     *     it holds none
     */
    public LoginDecision logIn(String username, String password, String retainedSessionId) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (AuthenticationException refused) {
            PasswordRefused refusal =
                    new PasswordRefused(username, PasswordRefusalReason.reportedFor(refused));
            outcomes.record(refusal, retainedSessionId);
            return new LoginDecision(refusal, Optional.empty());
        }

        // The User was read once, to authenticate; everything after works from the stable id
        // that read put on the principal, never from the name, which a directory rename can
        // move to another User in between. Outside the catch above on purpose: a failure
        // recording the success is not a refusal, and must not be reported to the caller as one.
        UUID userId = LoginPrincipal.userIdOf(authentication);
        Optional<LoginStateService.StandingRefusal> refusedOnStanding =
                attempts.recordPasswordSuccess(userId, retainedSessionId);
        if (refusedOnStanding.isPresent()) {
            // A Lockout or a deactivation committed while the password was being compared: the
            // refusal the authentication would have made a moment later, recorded as it would
            // have been — through the failure path, which counts nothing against a locked User.
            PasswordRefused refusal =
                    new PasswordRefused(username, refusedOnStanding.get().passwordReason());
            outcomes.record(refusal, retainedSessionId);
            return new LoginDecision(refusal, Optional.empty());
        }
        return LoginDecision.signedIn(accepted(authentication, SignedIn.password(userId)));
    }

    /**
     * The login decision for an Epic Login (ADR 0012, flow step 6): the clinician Epic proved, named
     * by the Practitioner ID its {@code fhirUser} carried, becomes an authentication — or a
     * refusal.
     *
     * <p>The User is the one whose stored {@code userName} equals the Practitioner ID exactly
     * ({@link LoginIdentityService#loadEpicLinkedUser}), and it is refused if there is none, or
     * it is deactivated or locked — Lockout and deactivation apply to Epic Login exactly as to
     * password Login (D12), and are checked again under the User's lock as the success is
     * recorded, so one committed after the User was read refuses the Login too. Otherwise its
     * authorities are those password Login gives the same User, and the success is recorded
     * exactly as password Login's is, under method {@code sso}: failure run cleared, dormancy
     * basis moved, {@code LOGIN_SUCCESS} fail-closed, and every other session of the User revoked
     * after the commit. All of it in one transaction, so a success the trail cannot record is not
     * a success.
     *
     * <p>No password is compared: Epic checked the credential, not us. Nothing Epic sent is kept
     * here: Epic's tokens are kept by the web adapter, on the session it signs in, only once this
     * has accepted the User (ADR 0012, D29); the Practitioner ID is not logged.
     *
     * <p>A refusal is recorded here, through {@link LoginOutcomeService}, as a password Login's
     * is, so no caller can refuse an Epic Login without the record: its {@code LOGIN_FAILURE} names its
     * {@link EpicLoginFailureReason} and the refused User's stable id — none for
     * {@code UNKNOWN_ACCOUNT}, whose ID is not recorded at all — and, unlike password Login's,
     * counts toward no failure run (D12). An acceptance is not: its {@code LOGIN_SUCCESS} is,
     * fail-closed, but the Login has not ended until the caller has signed the session in, so
     * the caller records the {@link SignedIn} it is handed once it has — after this transaction
     * commits, and naming the session the User goes on to use.
     *
     * @param practitionerId    the Practitioner ID Epic's {@code id_token} named
     * @param retainedSessionId the id the caller's session is stored under, which the Login
     *                          continues in; {@code null} when it holds none
     * @param mfaFactor         the MFA factor the Login was made with (D17), recorded on its
     *                          {@code LOGIN_SUCCESS}
     * @return how the decision ended — a {@link SignedIn} with the accepted Login, or a
     *     {@link EpicRefused}, already recorded, when no acceptable User is linked to the Practitioner
     *     ID: none matches it exactly, or the one that does is the Bootstrap Admin, deactivated
     *     or locked
     */
    @Transactional
    public LoginDecision logInFromEpic(
            String practitionerId, String retainedSessionId, AuditMfaFactor mfaFactor) {
        Optional<LoginPrincipal> linked = identities.loadEpicLinkedUser(practitionerId);
        if (linked.isEmpty()) {
            // No subject: the ID named nobody acceptable, and is itself never recorded.
            return refused(EpicRefused.account(null, EpicLoginFailureReason.UNKNOWN_ACCOUNT),
                    retainedSessionId);
        }
        // Read once; from here on the User is its stable id, as a password Login's is.
        LoginPrincipal user = linked.get();
        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            return refused(EpicRefused.account(user.userId(),
                    user.isEnabled()
                            ? EpicLoginFailureReason.ACCOUNT_LOCKED
                            : EpicLoginFailureReason.ACCOUNT_DISABLED), retainedSessionId);
        }
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        user, null, user.getAuthorities());
        // As ProviderManager does for a password Login: the session never carries the hash.
        authentication.eraseCredentials();
        Optional<LoginStateService.StandingRefusal> refusedOnStanding =
                attempts.recordEpicSuccess(user.userId(), retainedSessionId, mfaFactor);
        if (refusedOnStanding.isPresent()) {
            // Locked or deactivated since the read above, as a password Login's success can be.
            return refused(EpicRefused.account(user.userId(), refusedOnStanding.get().epicReason()),
                    retainedSessionId);
        }
        return LoginDecision.signedIn(
                accepted(authentication, SignedIn.epic(user.userId(), mfaFactor)));
    }

    private LoginDecision refused(EpicRefused refusal, String retainedSessionId) {
        outcomes.record(refusal, retainedSessionId);
        return new LoginDecision(refusal, Optional.empty());
    }

    /**
     * What the caller establishes the session from, once the success is recorded: the
     * authentication, and {@code signedIn}, naming the User by the stable id its principal
     * carries.
     */
    private AcceptedLogin accepted(Authentication authentication, SignedIn signedIn) {
        return new AcceptedLogin(authentication, signedIn);
    }

    /**
     * A successful login, carrying both what Spring Security needs to place in
     * the security context and the identity's stable id — the SCIM resource id the
     * web adapter writes into the session index, so application-owned session
     * lookups survive a later {@code userName} change instead of following
     * {@code authentication.getName()}. Its {@link SignedIn} is the outcome the caller records once the session is signed in, and names
     * the login method the session is signed in by.
     */
    public record AcceptedLogin(Authentication authentication, SignedIn signedIn) {

        /** The identity's stable id, which the session is indexed and logged by. */
        public UUID userId() {
            return signedIn.userId();
        }
    }

    /**
     * How a login decision ended, by either login method: its {@link LoginOutcome}, and the
     * accepted Login the caller establishes the session from — present exactly when the outcome
     * is {@link SignedIn}, and then that accepted Login's own. A refusal it carries is already
     * recorded; a {@link SignedIn} is the caller's to record, once the session is signed in.
     */
    public record LoginDecision(LoginOutcome outcome, Optional<AcceptedLogin> accepted) {

        public LoginDecision {
            if (accepted.map(login -> !login.signedIn().equals(outcome))
                    .orElse(outcome instanceof SignedIn)) {
                throw new IllegalArgumentException(
                        "exactly a signed-in Login carries the accepted Login it signed in");
            }
        }

        /** The decision that accepted {@code accepted}, ending in its {@link SignedIn}. */
        public static LoginDecision signedIn(AcceptedLogin accepted) {
            return new LoginDecision(accepted.signedIn(), Optional.of(accepted));
        }
    }
}
