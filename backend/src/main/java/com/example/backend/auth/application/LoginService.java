package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.application.LoginOutcome.EpicRefused;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
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
     * Authenticates the submitted credentials, counting the attempt against the
     * account.
     *
     * <p>A refusal is rethrown unchanged — wrong password, unknown username,
     * locked account, disabled account — so every one of them leaves through the
     * caller's single handler and answers with the same bare {@code 401}.
     *
     * <p>The run of failures an accepted login ends is cleared before this
     * returns, so a caller holding an authentication is by definition one whose
     * account was not refused, whatever it does with the authentication next.
     *
     * <p>Both outcomes are recorded through {@link LoginOutcomeService}, the one module that
     * records how a Login ended by either method, so no caller can refuse a Login without the
     * record — and neither record carries the submitted {@code username}. It is the single most
     * sensitive value passing through here — it is half a credential, and on a failed attempt it
     * is very often a mistyped password — so it stays out of the log, in the message and in the
     * context alike. The accepted attempt carries the identity's stable id as {@code user.id};
     * the refused one carries no user field at all, because the identity it named is unresolved,
     * and no reason either: it says only that the Login was refused (Logging §2.2). Whether the
     * password was wrong, the name unknown, or the account locked or deactivated tells whether
     * an account exists, so that is the audit trail's {@code LOGIN_FAILURE} alone, read by an
     * administrator, and the {@code login} counter's {@code reason} tag, which names no account.
     * A run of wrong passwords is still told from a run against names that do not exist, there.
     *
     * @throws AuthenticationException when the credentials are refused
     */
    public AcceptedLogin logIn(String username, String password) {
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
     * @throws AuthenticationException when the credentials are refused
     */
    public AcceptedLogin logIn(String username, String password, String retainedSessionId) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (AuthenticationException refused) {
            outcomes.record(new PasswordRefused(username, refusalReason(refused)),
                    retainedSessionId);
            throw refused;
        }

        // Outside the catch above on purpose: a failure recording the success is
        // not a refusal, and must not be reported to the caller as one.
        attempts.recordPasswordSuccess(authentication.getName(), retainedSessionId);
        AcceptedLogin accepted = accepted(authentication);
        // No session named: the one the caller goes on to sign in is not rotated yet.
        outcomes.record(SignedIn.password(accepted.userId()), null);
        return accepted;
    }

    /**
     * The login decision for an Epic Login (ADR 0013, flow step 6): the clinician Epic proved, named
     * by the Practitioner ID its {@code fhirUser} carried, becomes an authentication — or a
     * refusal.
     *
     * <p>The User is the one whose stored {@code userName} equals the Practitioner ID exactly
     * ({@link LoginIdentityService#loadEpicLinkedUser}), and it is refused if there is none, or
     * it is deactivated or locked — Lockout and deactivation apply to Epic Login exactly as to
     * password Login (D12). Otherwise its authorities are those password Login gives the same
     * User, and the success is recorded exactly as password Login's is, under method
     * {@code sso}: failure run cleared, dormancy basis moved, {@code LOGIN_SUCCESS} fail-closed,
     * and every other session of the User revoked after the commit. All of it in one
     * transaction, so a success the trail cannot record is not a success.
     *
     * <p>No password is compared: Epic checked the credential, not us. Nothing Epic sent is kept
     * here: Epic's tokens are kept by the web adapter, on the session it signs in, only once this
     * has accepted the User (ADR 0013's 2026-10-09 addendum); the Practitioner ID is not logged.
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
    public EpicLoginDecision logInFromEpic(
            String practitionerId, String retainedSessionId, AuditMfaFactor mfaFactor) {
        Optional<UserDetails> linked = identities.loadEpicLinkedUser(practitionerId);
        if (linked.isEmpty()) {
            // No subject: the ID named nobody acceptable, and is itself never recorded.
            return refused(EpicRefused.account(null, EpicLoginFailureReason.UNKNOWN_ACCOUNT),
                    retainedSessionId);
        }
        UserDetails user = linked.get();
        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            return refused(EpicRefused.account(identities.resolveUserId(user.getUsername()),
                    user.isEnabled()
                            ? EpicLoginFailureReason.ACCOUNT_LOCKED
                            : EpicLoginFailureReason.ACCOUNT_DISABLED), retainedSessionId);
        }
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        user, null, user.getAuthorities());
        // As ProviderManager does for a password Login: the session never carries the hash.
        authentication.eraseCredentials();
        attempts.recordEpicSuccess(authentication.getName(), retainedSessionId, mfaFactor);
        AcceptedLogin accepted = accepted(authentication);
        return new EpicLoginDecision(
                SignedIn.epic(accepted.userId(), mfaFactor), Optional.of(accepted));
    }

    private EpicLoginDecision refused(EpicRefused refusal, String retainedSessionId) {
        outcomes.record(refusal, retainedSessionId);
        return new EpicLoginDecision(refusal, Optional.empty());
    }

    /** What the caller establishes the session from, once the success is recorded. */
    private AcceptedLogin accepted(Authentication authentication) {
        return new AcceptedLogin(authentication, identities.resolveUserId(authentication.getName()),
                identities.roleMappingHash());
    }

    /**
     * The refusal as the audit trail's own vocabulary.
     *
     * <p>Translated here, at the one place a Spring Security
     * {@code AuthenticationException} is caught, so no other layer has to know the
     * library's exception hierarchy and no exception object — whose message may
     * name the submitted username — travels further than this method.
     *
     * <p>{@link AuditRefusalReason#UNKNOWN_ACCOUNT} is deliberately not produced
     * here. Spring Security hides a missing account behind
     * {@code BadCredentialsException} so that the two are indistinguishable to the
     * caller, which is the behaviour this service wants; whether the username named
     * an account is settled by {@link LoginAttemptService}, which has to look the
     * account up anyway and can tell without guessing from an exception type.
     */
    private static AuditRefusalReason refusalReason(AuthenticationException refused) {
        return switch (refused) {
            case LockedException locked -> AuditRefusalReason.ACCOUNT_LOCKED;
            case DisabledException disabled -> AuditRefusalReason.ACCOUNT_DISABLED;
            case BadCredentialsException wrong -> AuditRefusalReason.BAD_CREDENTIALS;
            default -> AuditRefusalReason.OTHER;
        };
    }

    /**
     * A successful login, carrying both what Spring Security needs to place in
     * the security context and the identity's stable id — the SCIM resource id the
     * web adapter writes into the session index, so application-owned session
     * lookups survive a later {@code userName} change instead of following
     * {@code authentication.getName()} — and the hash of the role mapping the
     * authentication's Permissions were resolved under, which the session records.
     */
    public record AcceptedLogin(
            Authentication authentication, UUID userId, String roleMappingHash) {
    }

    /**
     * How the Epic login decision ended: its {@link LoginOutcome}, and the accepted Login the
     * caller establishes the session from — present exactly when the outcome is
     * {@link SignedIn}.
     */
    public record EpicLoginDecision(LoginOutcome outcome, Optional<AcceptedLogin> accepted) {

        public EpicLoginDecision {
            if (accepted.isPresent() != outcome instanceof SignedIn) {
                throw new IllegalArgumentException("only a signed-in Login carries a session");
            }
        }
    }
}
