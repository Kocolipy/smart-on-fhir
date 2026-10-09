package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * locked identity to Spring Security, which refuses it before any password is
 * compared. This module records what happened; the rule for what counts as
 * locked lives in {@link com.example.backend.scim.domain.ScimLoginState}.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService attempts;
    private final LoginIdentityService identities;

    public LoginService(
            AuthenticationManager authenticationManager,
            LoginAttemptService attempts,
            LoginIdentityService identities) {
        this.authenticationManager = authenticationManager;
        this.attempts = attempts;
        this.identities = identities;
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
     * <p>Both outcomes are logged, and neither record carries the submitted
     * {@code username}. It is the single most sensitive value passing through
     * here — it is half a credential, and on a failed attempt it is very often a
     * mistyped password — so it stays out of the log, in the message and in the
     * context alike. The accepted attempt carries the identity's stable id as
     * {@code user.id}; the refused one carries no user field at all, because the
     * identity it named is unresolved. What the refusal carries instead is the
     * type of refusal, which is what tells a run of wrong passwords from a run
     * against names that do not exist.
     *
     * @throws AuthenticationException when the credentials are refused
     */
    public LoginOutcome logIn(String username, String password) {
        return logIn(username, password, null);
    }

    /**
     * {@link #logIn(String, String)} from a caller that may already hold a session: an accepted
     * login ends every other session of the identity once the cleared failure run commits, and
     * keeps this one, which the caller goes on to rotate and sign in.
     *
     * @param retainedSessionId the id the caller's session is stored under, or {@code null} when
     *     it holds none
     * @throws AuthenticationException when the credentials are refused
     */
    public LoginOutcome logIn(String username, String password, String retainedSessionId) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (AuthenticationException refused) {
            attempts.recordFailure(username, refusalReason(refused));
            // The exception's own type, not its message: a message can carry the
            // submitted value, and a type name is this service's own vocabulary.
            // No user field: the attempt's identity is unresolved, and a session the
            // request happened to carry is not whom the attempt was for.
            try (LogContext.Scope unresolved = LogContext.userId(null)) {
                LogEvent.refused(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.DENIED)
                        .addKeyValue(LogEvent.REASON, refused.getClass().getSimpleName())
                        .log();
            }
            throw refused;
        }

        // Outside the catch above on purpose: a failure recording the success is
        // not a refusal, and must not be reported to the caller as one.
        attempts.recordPasswordSuccess(authentication.getName(), retainedSessionId);
        return succeeded(authentication, AuditLoginMethod.PASSWORD);
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
     * (D8); the Practitioner ID is not logged.
     *
     * <p>A refusal is recorded here, once, as password Login's is, so no caller can refuse an
     * Epic Login without the record: a {@code LOGIN_FAILURE} under method {@code sso} with its
     * {@link EpicLoginFailureReason} and the refused User's stable id — none for
     * {@code UNKNOWN_ACCOUNT}, whose ID is not recorded at all — and a {@code WARN} that says only
     * "Epic sign-in refused", because the reason tells whether an account exists. Unlike password
     * Login's, it counts toward no failure run (D12).
     *
     * @param practitionerId    the Practitioner ID Epic's {@code id_token} named
     * @param retainedSessionId the id the caller's session is stored under, which the Login
     *                          continues in; {@code null} when it holds none
     * @param mfaFactor         the MFA factor the Login was made with (D17), recorded on its
     *                          {@code LOGIN_SUCCESS}
     * @throws EpicLoginRefusedException when no acceptable User is linked to the Practitioner ID:
     *     none matches it exactly, or the one that does is the Bootstrap Admin, deactivated or
     *     locked
     */
    @Transactional
    public LoginOutcome logInFromEpic(
            String practitionerId, String retainedSessionId, AuditMfaFactor mfaFactor) {
        Optional<UserDetails> linked = identities.loadEpicLinkedUser(practitionerId);
        if (linked.isEmpty()) {
            // No subject: the ID named nobody acceptable, and is itself never recorded.
            throw refusedEpicLogin(null, EpicLoginFailureReason.UNKNOWN_ACCOUNT);
        }
        UserDetails user = linked.get();
        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            throw refusedEpicLogin(identities.resolveUserId(user.getUsername()),
                    user.isEnabled()
                            ? EpicLoginFailureReason.ACCOUNT_LOCKED
                            : EpicLoginFailureReason.ACCOUNT_DISABLED);
        }
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        user, null, user.getAuthorities());
        // As ProviderManager does for a password Login: the session never carries the hash.
        authentication.eraseCredentials();
        attempts.recordEpicSuccess(authentication.getName(), retainedSessionId, mfaFactor);
        return succeeded(authentication, AuditLoginMethod.SSO);
    }

    /**
     * An Epic Login refused by the login decision, recorded once: a {@code LOGIN_FAILURE} under
     * method {@code sso} naming the reason and the refused User, which counts toward no failure
     * run (D12).
     *
     * @param subjectId the refused User's stable id, or {@code null} when there is none to name
     * @return the refusal, for the caller to throw
     */
    private EpicLoginRefusedException refusedEpicLogin(
            UUID subjectId, EpicLoginFailureReason reason) {
        attempts.recordRefusal(subjectId, audited(reason), AuditLoginMethod.SSO);
        // Generic on purpose: the reason tells whether an account exists, so it is the audit
        // trail's alone (ADR 0013, "the account reasons are audit-only"), and no user field —
        // the refused User is named there, and a session the browser happened to carry is not
        // whom the launch was for.
        try (LogContext.Scope unresolved = LogContext.userId(null)) {
            LogEvent.refused(log, Operation.EPIC_LOGIN, Category.PROCESS, Type.USER, Type.DENIED)
                    .addKeyValue(LogEvent.LOGIN_METHOD, AuditLoginMethod.SSO.value())
                    .log();
        }
        return new EpicLoginRefusedException(reason);
    }

    /**
     * Records an Epic Login that ended before any login decision, for {@code reason}: a
     * {@code LOGIN_FAILURE} under method {@code sso} naming nobody, since no User was ever
     * resolved, which counts toward no failure run (D12). Epic being unavailable (D23) ends a
     * Login so, and so does every protocol refusal: a launch, callback or {@code id_token} that
     * failed its checks before any User was looked up.
     *
     * <p>The record only: the Epic failure handler, which knows how the Login ended, writes its
     * one log record itself and sends the browser on (D24).
     */
    public void recordEpicFailure(EpicLoginFailureReason reason) {
        attempts.recordRefusal(null, audited(reason), AuditLoginMethod.SSO);
    }

    /**
     * An Epic refusal as the audit trail's own vocabulary, which password Login's refusals share.
     * Exhaustive, so a reason added to the list cannot reach the trail unmapped.
     */
    private static AuditRefusalReason audited(EpicLoginFailureReason reason) {
        return switch (reason) {
            case INVALID_LAUNCH -> AuditRefusalReason.INVALID_LAUNCH;
            case ISS_MISMATCH -> AuditRefusalReason.ISS_MISMATCH;
            case INVALID_STATE -> AuditRefusalReason.INVALID_STATE;
            case INVALID_CODE -> AuditRefusalReason.INVALID_CODE;
            case IDP_ERROR -> AuditRefusalReason.IDP_ERROR;
            case TOKEN_EXCHANGE_FAILED -> AuditRefusalReason.TOKEN_EXCHANGE_FAILED;
            case INVALID_SIGNATURE -> AuditRefusalReason.INVALID_SIGNATURE;
            case INVALID_CLAIMS -> AuditRefusalReason.INVALID_CLAIMS;
            case INVALID_FHIR_USER -> AuditRefusalReason.INVALID_FHIR_USER;
            case EPIC_UNAVAILABLE -> AuditRefusalReason.EPIC_UNAVAILABLE;
            case UNKNOWN_ACCOUNT -> AuditRefusalReason.UNKNOWN_ACCOUNT;
            case ACCOUNT_DISABLED -> AuditRefusalReason.ACCOUNT_DISABLED;
            case ACCOUNT_LOCKED -> AuditRefusalReason.ACCOUNT_LOCKED;
        };
    }

    /**
     * The tail every accepted Login shares once its success is recorded against the User,
     * whichever way it proved who signed in: the {@code LOGIN} record naming the User and the
     * login method (D15), and the outcome the caller establishes the session from.
     */
    private LoginOutcome succeeded(Authentication authentication, AuditLoginMethod method) {
        UUID userId = identities.resolveUserId(authentication.getName());
        // Set explicitly: the session's principal index that carries user.id for later
        // requests is written only after this returns.
        try (LogContext.Scope resolved = LogContext.userId(userId)) {
            LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED)
                    .addKeyValue(LogEvent.LOGIN_METHOD, method.value())
                    .log();
        }
        return new LoginOutcome(authentication, userId, identities.roleMappingHash());
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
    public record LoginOutcome(
            Authentication authentication, UUID userId, String roleMappingHash) {
    }
}
