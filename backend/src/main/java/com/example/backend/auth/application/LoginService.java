package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditLoginStateAttribute;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.application.LoginOutcome.EpicRefused;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.application.LoginStateService.Verification;
import com.example.backend.auth.application.LoginStateService.VerifiedCredential;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Logging in: submitted credentials become either an authentication or a refusal, and the attempt
 * is counted against the account either way. The one module a password Login runs through — it
 * authenticates, decides under the User's lock, counts a failure and writes the Login's audit
 * record — and the one an Epic Login is decided by.
 *
 * <p>The counting is here rather than at the call site because it is part of what logging in
 * <em>is</em> — an entry point that authenticated credentials without it would silently have no
 * lockout. A caller therefore cannot obtain an authentication from submitted credentials and skip
 * the failure run; it gets both or neither.
 *
 * <p>What an attempt does to the login state — the failure run, the Lockout, the dormancy basis —
 * is not decided here: each attempt is reported to {@link LoginStateService}, which decides the
 * new state under the User's lock, writes it and, on a newly imposed Lockout, audits it and
 * schedules the revocation of every Session. What this records is the Login's own: a refusal's
 * {@code LOGIN_FAILURE}, an acceptance's fail-closed {@code LOGIN_SUCCESS}, and an acceptance's
 * one-session-per-User sweep, through {@link SessionRevocationService}. Enforcement is elsewhere
 * too: {@link LoginIdentityService} reports a locked identity to Spring Security, which refuses
 * it whatever the password, after comparing that password exactly as it would compare a wrong
 * one; the rule for what counts as locked lives in
 * {@link com.example.backend.scim.domain.ScimLoginState}.
 *
 * <h2>One transaction after the comparison</h2>
 *
 * <p>A password Login's Argon2id comparison runs in no transaction: it is slow by design, and a
 * transaction open across it would hold a pooled connection for its whole length. So
 * {@link #logIn} opens none around it. Once the authentication has answered, everything else runs
 * in one transaction, which {@link #logIn} opens itself — a {@code @Transactional} helper it
 * called would never pass through the proxy:
 *
 * <ul>
 *   <li>a refusal by the authentication is counted, and its {@code LOGIN_FAILURE} written;
 *   <li>an acceptance is decided under the User's lock
 *       ({@link LoginStateService#passwordLoginVerified}) and either recorded — the cleared run,
 *       the {@code LOGIN_SUCCESS}, the sweep scheduled for after the commit — or refused there,
 *       that refusal counted under the same lock, and its {@code LOGIN_FAILURE} written.
 * </ul>
 *
 * <p>A refusal is therefore decided and counted in one transaction, never decided in one and
 * counted in a second that takes the lock again. Every {@code LOGIN_FAILURE} is fail-open, as
 * everything on a refusal's path is: the caller is already being answered a bare {@code 401}, and
 * a trail that cannot be written must not change that answer. The {@code LOGIN_SUCCESS} is
 * fail-closed and joins the transaction, so a success the trail cannot record takes the cleared
 * run down with it, and is not a success ({@link AuditTrail}; ADR 0004). The transaction is not
 * retried on a deadlock, and neither is an Epic Login's: a Login is neither a SCIM nor an
 * administrative write, and the person signing in tries again.
 */
@Service
public class LoginService {

    private final AuthenticationManager authenticationManager;
    private final LoginIdentityService identities;
    private final ScimUserRepository users;
    private final LoginStateService loginState;
    private final SessionRevocationService sessions;
    private final AuditTrail audit;
    private final LoginOutcomeService outcomes;

    /**
     * A password Login's one transaction, once the authentication has answered: the template's
     * default propagation, {@code PROPAGATION_REQUIRED}, which opens it, since {@link #logIn} runs
     * in none.
     */
    private final TransactionTemplate decision;

    /**
     * @param transactionManager the primary transaction manager, which commits a password Login's
     *     decision, its count and its audit record together
     */
    public LoginService(
            AuthenticationManager authenticationManager,
            LoginIdentityService identities,
            ScimUserRepository users,
            LoginStateService loginState,
            SessionRevocationService sessions,
            AuditTrail audit,
            LoginOutcomeService outcomes,
            PlatformTransactionManager transactionManager) {
        this.authenticationManager = authenticationManager;
        this.identities = identities;
        this.users = users;
        this.loginState = loginState;
        this.sessions = sessions;
        this.audit = audit;
        this.outcomes = outcomes;
        this.decision = new TransactionTemplate(transactionManager);
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
     * authentication next. Every other session of the User ends once that commits — one
     * concurrent session per User, so a credential cannot be in use from two places at once — on
     * the path a Lockout's revocation takes and for the same reason
     * ({@code /docs/adr/0002-revoke-sessions-after-commit.md}): a rolled-back Login must not have
     * signed its owner out everywhere else. The rule holds for a Login confined by a required
     * change as for any other.
     *
     * <p>Spring Security's check of a locked or deactivated account reads the User before the
     * login-state lock, and the password comparison after it takes long enough for another
     * attempt's Lockout, a connector's deactivation, or a replacement of the very password being
     * compared, to commit in between. The success is therefore decided once more on the state
     * read under the lock ({@link LoginStateService#passwordLoginVerified}); a Login refused there
     * ends as the refusal the check would have made a moment later — {@code ACCOUNT_LOCKED} or
     * {@code ACCOUNT_DISABLED}, recorded and counted exactly as that refusal is — so it never
     * lifts the Lockout it lost the race to (ADR 0007), or, for a replaced password, as the wrong
     * password it is by then: {@code BAD_CREDENTIALS}, counted on the failure run. It is counted
     * under the lock it was decided under, in the same transaction. A User deleted in that window
     * is refused as one gone under the lock is, and recorded against nobody
     * ({@code UNKNOWN_ACCOUNT}), with nothing else written. Whether the session it issues is
     * confined is read from that same decision, never from the read before the lock.
     *
     * <p>Every refusal is counted and audited against the User the authentication's one read
     * resolved, by stable id — the authentication tells this which, as it reads
     * ({@link PasswordLoginAttempt}) — or against nobody when that read found no User, or the
     * User it found is gone by the time the refusal is recorded; the submitted name is never
     * looked up again. A name that resolved nobody is recorded rather than counted: nothing is
     * created for it, so a caller cannot learn from stored state whether the name exists, and its
     * {@code LOGIN_FAILURE} carries no subject, because the submitted username is the one thing
     * that must not be recorded in its place. An administrator reading a run of subject-less
     * failures is seeing attempts against names that do not exist, which is exactly the
     * distinction worth having.
     *
     * <p>A refusal's log line and counts are written through {@link LoginOutcomeService}, the one
     * module that logs and counts how a Login ended by either method, once its transaction has
     * committed. An acceptance's are not: the Login has not ended until the caller has signed the
     * session in, so the caller records the {@link SignedIn} it is handed once it has, naming the
     * session the User goes on to use ({@code LoginCompletion}; ADR 0001). Neither record carries
     * the submitted {@code username}. It is
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
        PasswordLoginAttempt attempt = new PasswordLoginAttempt(username, password);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(attempt);
        } catch (AuthenticationException refused) {
            // Against the User the authentication's one read resolved, by stable id, or nobody:
            // the submitted name is never looked up again, so a rename landing since the read
            // cannot move the refusal, or the Lockout it may impose, onto another User.
            UUID resolved = attempt.resolvedUserId();
            PasswordRefusalReason reason = PasswordRefusalReason.reportedFor(refused);
            return ended(decision.execute(status -> refusalCounted(resolved, reason)),
                    retainedSessionId);
        }

        // The User was read once, to authenticate; everything after works from the stable id
        // that read put on the principal, never from the name, which a directory rename can
        // move to another User in between. Outside the catch above on purpose: a failure
        // recording the success is not a refusal, and must not be reported to the caller as one.
        UUID userId = LoginPrincipal.userIdOf(authentication);
        VerifiedCredential credential = attempt.verifiedCredential();
        return ended(decision.execute(status ->
                        decided(authentication, userId, credential, retainedSessionId)),
                retainedSessionId);
    }

    /**
     * A refusal by the authentication: counted against the User {@code resolvedUserId} names, if
     * it is still there, and recorded. In the Login's one transaction.
     */
    private LoginDecision refusalCounted(UUID resolvedUserId, PasswordRefusalReason reason) {
        Optional<ScimUser> found = Optional.ofNullable(resolvedUserId).flatMap(users::findById);
        Set<AuditLoginStateAttribute> moved = found
                .map(user -> loginState.failureCounted(user, reason))
                .map(LoginStateChange::moved)
                .orElse(Set.of());
        return refusalRecorded(
                new PasswordRefused(found.map(ScimUser::id).orElse(null), reason), moved);
    }

    /**
     * An accepted authentication of the User {@code userId}, decided under its lock: recorded as
     * a success, or refused there and that refusal counted under the same lock. In the Login's
     * one transaction.
     */
    private LoginDecision decided(Authentication authentication, UUID userId,
            VerifiedCredential credential, String retainedSessionId) {
        Optional<ScimUser> found = users.findById(userId);
        if (found.isEmpty()) {
            // Deleted while the password was being compared: refused as a User gone under its
            // lock is, and recorded against nobody.
            return refusalRecorded(
                    new PasswordRefused(null, Verification.userGone().passwordRefusal()), Set.of());
        }
        ScimUser user = found.get();
        Verification verification = loginState.passwordLoginVerified(user, credential);
        if (verification.refused()) {
            // A Lockout or a deactivation committed while the password was being compared — the
            // refusal the authentication would have made a moment later — or a password replaced
            // meanwhile, which makes the submitted one wrong: counted already, as that refusal
            // is, on the state it was decided from.
            return refusalRecorded(
                    new PasswordRefused(user.id(), verification.passwordRefusal()),
                    verification.change().moved());
        }
        signedInRecorded(new Audited.LoginSuccess(user.id(), AuditLoginMethod.PASSWORD),
                user.id(), retainedSessionId);
        return LoginDecision.signedIn(accepted(
                identities.issued(authentication, verification.confined()),
                SignedIn.password(userId)));
    }

    /**
     * Writes {@code refusal}'s {@code LOGIN_FAILURE}, fail-open, naming what counting it
     * {@code moved}, and hands back the decision that ends in it.
     */
    private LoginDecision refusalRecorded(
            PasswordRefused refusal, Set<AuditLoginStateAttribute> moved) {
        audit.append(new Audited.LoginFailure(refusal.subjectId(), refusal.recorded().audited(),
                AuditLoginMethod.PASSWORD, moved));
        return new LoginDecision(refusal, Optional.empty());
    }

    /**
     * A password Login's decision once its transaction has committed: a refusal's log line and
     * counts written, an acceptance handed back for its caller to record once signed in.
     */
    private LoginDecision ended(LoginDecision decided, String retainedSessionId) {
        if (decided.outcome() instanceof PasswordRefused refusal) {
            outcomes.record(refusal, retainedSessionId);
        }
        return decided;
    }

    /**
     * The login decision for an Epic Login (ADR 0012, flow step 6): the clinician Epic proved, named
     * by the Practitioner ID its {@code fhirUser} carried, becomes an authentication — or a
     * refusal.
     *
     * <p>The User is the one whose stored {@code userName} equals the Practitioner ID exactly
     * ({@link LoginIdentityService#loadEpicLinkedUser}), and it is refused if there is none, or
     * it is locked or deactivated — Lockout and deactivation apply to Epic Login exactly as to
     * password Login (D12), and are checked once, under the User's lock as the success is
     * recorded ({@link LoginStateService#credentialVerified}), in the login-state module's order
     * (locked first), so one committed after the User was read refuses the Login too; so is a User
     * deleted since it was read, with nothing written. Otherwise its
     * authorities are those password Login gives the same User, and the success is recorded
     * exactly as password Login's is, under method {@code sso}: failure run cleared, dormancy
     * basis moved, {@code LOGIN_SUCCESS} fail-closed, and every other session of the User revoked
     * after the commit. All of it in one transaction, so a success the trail cannot record is not
     * a success. It differs in the MFA factor its {@code LOGIN_SUCCESS} carries (D17), and in
     * moving the dormancy basis even while the User's change-required flag is set: an Epic Login
     * is never confined by the flag, so it is real use of the account. The flag itself is left
     * set: only a successful password change clears it.
     *
     * <p>No password is compared: Epic checked the credential, not us. Nothing Epic sent is kept
     * here: Epic's tokens are kept by the web adapter, on the session it signs in, only once this
     * has accepted the User (ADR 0012, D29); the Practitioner ID is not logged.
     *
     * <p>A refusal is recorded here, through {@link LoginOutcomeService}, so no caller can refuse
     * an Epic Login without the record: its {@code LOGIN_FAILURE} names its
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
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        user, null, user.getAuthorities());
        // As ProviderManager does for a password Login: the session never carries the hash.
        authentication.eraseCredentials();
        Objects.requireNonNull(mfaFactor, "an Epic Login always carries an MFA factor (D17)");
        // Locked or deactivated, decided once, under the User's lock and in the module's one
        // order: the read above is not checked first, so the two cannot disagree. Epic presented
        // no credential of ours, so none can have been replaced.
        Verification verification = users.findById(user.userId())
                .map(found -> loginState.credentialVerified(found, VerifiedCredential.epic()))
                .orElseGet(Verification::userGone);
        if (verification.refused()) {
            return refused(EpicRefused.account(user.userId(), verification.refusal().epicReason()),
                    retainedSessionId);
        }
        signedInRecorded(new Audited.LoginSuccess(user.userId(), AuditLoginMethod.SSO, mfaFactor),
                user.userId(), retainedSessionId);
        return LoginDecision.signedIn(accepted(
                identities.issued(authentication, verification.confined()),
                SignedIn.epic(user.userId(), mfaFactor)));
    }

    private LoginDecision refused(EpicRefused refusal, String retainedSessionId) {
        outcomes.record(refusal, retainedSessionId);
        return new LoginDecision(refusal, Optional.empty());
    }

    /**
     * Records an accepted Login of either method, decided under the User's lock, in its
     * transaction: its {@code LOGIN_SUCCESS}, fail-closed, so a success the trail cannot record
     * rolls back with the cleared run; and the end of every other session of {@code userId} but
     * {@code retainedSessionId}, once that commits.
     */
    private void signedInRecorded(
            Audited.LoginSuccess success, UUID userId, String retainedSessionId) {
        audit.append(success);
        sessions.revokeOtherSessionsAfterCommit(userId, retainedSessionId);
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
