package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserLoginState;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The login-state module: the one owner of a User's Failure run and lock, its dormancy basis and
 * its change-required flag. A caller reports what happened — a failure counted, a credential
 * verified, a password changed, an Unlock, a dormancy lock, a directory write — and this decides
 * the resulting state and writes what it changed, or hands the state back to be stored with the
 * write it belongs to. No caller decides those fields itself.
 *
 * <p>The rules, event by event:
 *
 * <ul>
 *   <li><b>Failure counted</b> — a refused password Login or a self-service change's wrong current
 *       password: the run lengthens, and reaching the {@link LockoutPolicy}'s limit imposes a
 *       Lockout for failures as of now. A User already locked is left as it is, whatever its lock
 *       cause. The Bootstrap Admin's failures are counted and never lock, read off its own
 *       reservation marker ({@code /docs/adr/0007-lockout-lifted-only-by-admin-unlock.md}). A
 *       User with no password has no run, so nothing is counted against it.
 *   <li><b>Credential verified</b> — an accepted Login: the run is cleared, and the dormancy basis
 *       moves unless the Login builds a Confined session (ADR 0008). A User found
 *       locked, deactivated or gone under its lock is refused instead, and nothing is written: a
 *       Login never lifts a Lockout (ADR 0007). So is a password Login whose verified credential
 *       is no longer the stored one, as a wrong password, for its caller to count.
 *   <li><b>Password changed</b> — the run is cleared, the change is completed (the new credential
 *       stored and the flag cleared in one write), and the dormancy basis moves.
 *   <li><b>Unlocked</b> — an Admin Unlock: the run and the Lockout are cleared, the dormancy
 *       basis restarts (ADR 0007) and, for a User with a password, a change is required. A User
 *       serving no lockout is left exactly as stored and nothing is written; the administration
 *       path refuses that Unlock on the state this read.
 *   <li><b>Dormancy-locked</b> — the dormancy job found a User due: it is locked for dormancy, the
 *       run untouched; an existing lock and its cause are never overwritten (ADR 0007).
 * </ul>
 *
 * <h2>The directory's events</h2>
 *
 * <p>The directory cannot name this module — the dependency between {@code scim} and
 * {@code auth} runs one way — so it asks through the {@link ScimUserLoginState} port this
 * implements, as it reaches Session revocation through {@code ScimUserSessions}:
 *
 * <ul>
 *   <li><b>Created</b> — a User created with a credential somebody else chose owes a change.
 *   <li><b>Replaced</b> — a SCIM replacement, PUT and PATCH alike. A connector-set password owes a
 *       change, and a <b>Reactivation</b> — {@code active} from false to true — restarts the
 *       dormancy basis and, for a User with a password, requires a change. True over true is no
 *       Reactivation. The state is returned and stored by the replacement's own row write, which
 *       stores what it is given.
 *   <li><b>Dormant fixture reset</b> — the development seed's dormant User, put back each startup.
 * </ul>
 *
 * <h2>Confinement</h2>
 *
 * <p>Whether a Login builds a Confined session is decided here
 * ({@link VerifiedCredential#confines}), once, on the state read under the User's lock, and both
 * of its consequences read that one answer: the dormancy basis this module moves, and the
 * authorities {@link LoginIdentityService} issues the session with
 * ({@link Verification#confined}). A password Login by a User whose flag is set is confined; an
 * Epic Login never is, because it presented no password of ours (D20), so it moves the basis while
 * the flag stays set — only a successful password change clears it.
 *
 * <h2>What a newly imposed Lockout implies</h2>
 *
 * <p>The transition into a Lockout — and only the transition — is audited as {@code LOCKOUT_SET}
 * and ends every Session of the User, in that order after the new state is written, so a counting
 * path's own refusal event, recorded once this returns, lands last. The revocation is the Session
 * revocation module's ({@link SessionRevocationService}) under
 * {@link SessionRevocationCause#FAILURE_RUN_LOCKOUT}: it runs after the transaction commits,
 * because Redis is not in the transaction ({@code /docs/adr/0002-revoke-sessions-after-commit.md}),
 * and it is audited under that cause. It is the one revocation a refusal imposes, so a session
 * store that fails is recorded and alerted without changing the path's bare refusal (ADR 0004).
 * The {@code LOCKOUT_SET} append is fail-open, as everything on the failure
 * path is ({@link AuditTrail}).
 *
 * <h2>Narrow writes</h2>
 *
 * <p>Every transition is encoded once, as a transition on {@link ScimLoginState}, and written as
 * what it changed: the state read under the User's lock and the state decided go to
 * {@link ScimLoginStateRepository#writeChange}, which writes only the columns that differ — so no
 * event here names a column, a field the decision leaves unchanged is not written at all, and the
 * write never reverts a concurrent directory write or moves the ETag. Two writes are operations of
 * their own because they are not plain column diffs:
 * {@link ScimLoginStateRepository#imposeLock}, the dormancy lock's compare-and-set, and
 * {@link ScimLoginStateRepository#completePasswordChange}, which replaces the credential and
 * advances the version. Never a full-row write. A directory create or replacement is not one of
 * these writes: the state this hands back rides the profile's own row write, which writes only the
 * columns that differ.
 *
 * <h2>What each transition hands back</h2>
 *
 * <p>Every transition answers with what it changed, in one shape: a {@link LoginStateChange}, the
 * state read under the User's lock and the state it left stored. What it moved
 * ({@link LoginStateChange#moved}) is the columns that differ, read by the rule {@link ScimLoginStateRepository#writeChange} writes by, so they are
 * exactly what was written — and every audit row a transition produces names its changed paths
 * from them, never from a list of its own: a refused Login that lengthened the run names
 * {@code failedLoginAttempts}, and one that counted nothing — a locked User's — names nothing
 * ({@code /docs/domain/authentication.md}). A transition that moved nothing hands back no path, and
 * a completed password change also says when it was completed, which is how its row names the
 * credential. The directory's events answer in the same shape, which lives beside their port in
 * {@code scim.domain} so the port can name it: a create or a replacement hands back the change
 * whose {@link LoginStateChange#after} its own row write stores, and the dormant fixture's reset
 * the change it wrote. None of the three records a login-state row.
 *
 * <h2>Serialization</h2>
 *
 * <p>Every event that writes the login state is serialized here, so no caller decides whether to
 * lock: each takes the User's resource lock through {@link ScimWriteLocks} and decides from the
 * state re-read under it ({@link ScimLoginStateRepository#findLoginState}), never from the copy of
 * the User it was handed. That copy names the User — its id, and the reservation marker the
 * Bootstrap Admin's exemption is read off, neither of which a write changes — and nothing else is
 * taken from it. Two events on one User therefore end as one serial order of them would:
 *
 * <ul>
 *   <li>N parallel failures against a User one short of the limit impose exactly one Lockout,
 *       audited once and revoking once; the attempts behind it find the User locked and are not
 *       counted, so the run ends at the limit — the cap spec C6 allows, and the rule that a
 *       locked User's attempts neither count nor deepen it ({@code /docs/domain/authentication.md});
 *   <li>parallel failures below the limit are each counted;
 *   <li>an Unlock racing a failure ends either locked-then-lifted or cleared-then-counted, never
 *       with a lock lifted that the Unlock did not see.
 * </ul>
 *
 * <p>The re-read is a statement of its own because the lock cannot always come first: a Login
 * must find its User by name before it can name the row, so the transaction may already hold an
 * older copy, which an ordinary lookup by id would hand back. A caller already holding the lock —
 * the password change, the dormancy job's batch in canonical order — loses nothing: taking a row
 * the transaction holds waits for nobody. {@code LoginStateSerializationIntegrationTests} proves
 * the guarantee against a real database; the in-memory stores cannot.
 *
 * <p>Each event joins its caller's transaction rather than opening one: the caller decides what
 * commits with it, and the lock is held until it does. Counting is still triggered explicitly
 * from the Login and password-change paths
 * ({@code /docs/adr/0001-count-attempts-and-complete-every-login-on-the-login-path.md}); nothing here listens for
 * events.
 */
@Service
public class LoginStateService implements ScimUserLoginState {

    /**
     * The credential an accepted authentication verified, which the Login is decided on under the
     * User's lock: a password Login stands only while the credential it compared against is still
     * the one stored; an Epic Login presented no password of ours (D20), so nothing it verified
     * can have been replaced.
     */
    public sealed interface VerifiedCredential {

        /**
         * Whether a Login that verified this credential, by a User in {@code state}, builds a
         * Confined session: one that may only change the password or log out.
         */
        boolean confines(ScimLoginState state);

        /** Whether {@code state}, read under the User's lock, still holds this credential. */
        boolean storedIn(ScimLoginState state);

        /**
         * The stored credential {@code passwordHash} a password Login's password was compared
         * against and matched. A salted hash differs on every write, so a credential replaced
         * since the comparison — even by the same password — is told apart from it.
         */
        static VerifiedCredential passwordHash(String passwordHash) {
            return new VerifiedPassword(passwordHash);
        }

        /** An Epic Login's: Epic checked the credential, not us. */
        static VerifiedCredential epic() {
            return new VerifiedByEpic();
        }
    }

    /**
     * A password Login's verified credential. Held only for the decision, never in the session,
     * and never printed.
     */
    private record VerifiedPassword(String passwordHash) implements VerifiedCredential {

        private VerifiedPassword {
            Objects.requireNonNull(passwordHash, "a password Login verified a stored credential");
        }

        /** The change-required flag marks the very credential the Login presented. */
        @Override
        public boolean confines(ScimLoginState state) {
            return state.isPasswordChangeRequired();
        }

        @Override
        public boolean storedIn(ScimLoginState state) {
            return passwordHash.equals(state.passwordHash());
        }

        @Override
        public String toString() {
            return "VerifiedPassword[redacted]";
        }
    }

    /** An Epic Login's verified credential: none of ours, so none that can have been replaced. */
    private record VerifiedByEpic() implements VerifiedCredential {

        /** It presented no password of ours, so the flag does not confine it. */
        @Override
        public boolean confines(ScimLoginState state) {
            return false;
        }

        @Override
        public boolean storedIn(ScimLoginState state) {
            return true;
        }
    }

    /**
     * The standing an accepted Login is refused on when the module finds it under the User's lock:
     * what the authentication's own check, made on a read taken before the lock, refuses — so a
     * Lockout or a deactivation committed while the password was being compared still refuses it.
     *
     * <p>Each standing carries the refusal reason both login routes and the self-service password
     * change report it as, so a standing added here cannot be reported by one and missed by
     * another; and {@link #of} is the one order they are decided in.
     */
    public enum StandingRefusal {
        /** A Lockout stands, of either cause. */
        LOCKED(PasswordRefusalReason.ACCOUNT_LOCKED, EpicLoginFailureReason.ACCOUNT_LOCKED,
                AuditPasswordChangeRefusal.ACCOUNT_LOCKED),
        /** The User is no longer active, or no longer there. */
        DEACTIVATED(PasswordRefusalReason.ACCOUNT_DISABLED, EpicLoginFailureReason.ACCOUNT_DISABLED,
                AuditPasswordChangeRefusal.ACCOUNT_DISABLED);

        private final PasswordRefusalReason passwordReason;
        private final EpicLoginFailureReason epicReason;
        private final AuditPasswordChangeRefusal passwordChangeReason;

        StandingRefusal(PasswordRefusalReason passwordReason, EpicLoginFailureReason epicReason,
                AuditPasswordChangeRefusal passwordChangeReason) {
            this.passwordReason = passwordReason;
            this.epicReason = epicReason;
            this.passwordChangeReason = passwordChangeReason;
        }

        /**
         * The standing that refuses a User in {@code state}, active or not: locked first, as the
         * authentication checks it, so a User both locked and deactivated is refused as locked by
         * every route. Empty when it refuses nothing.
         */
        public static Optional<StandingRefusal> of(ScimLoginState state, boolean active) {
            if (state.isLocked()) {
                return Optional.of(LOCKED);
            }
            return active ? Optional.empty() : Optional.of(DEACTIVATED);
        }

        /** The reason a self-service password change refused on this standing is recorded as. */
        public AuditPasswordChangeRefusal passwordChangeReason() {
            return passwordChangeReason;
        }

        /** The reason a password Login refused on this standing is reported and recorded as. */
        public PasswordRefusalReason passwordReason() {
            return passwordReason;
        }

        /** The reason an Epic Login refused on this standing is reported and recorded as. */
        public EpicLoginFailureReason epicReason() {
            return epicReason;
        }
    }

    /**
     * What a verified credential came to, decided once on the state read under the User's lock:
     * the login state it changed and whether the session it issues is confined, or, when that
     * state refused the Login, why, and a change that moved nothing.
     *
     * @param change              what the Login moved; nothing when it was refused
     * @param refusal             the standing the Login was refused on, or {@code null}
     * @param credentialReplaced  whether the stored credential was no longer the one the
     *     authentication verified, which refuses a password Login as a wrong password
     * @param confined            whether the accepted Login builds a Confined session — the answer
     *     that also left the dormancy basis where it was; {@code false} for a refused one
     */
    public record Verification(LoginStateChange change, StandingRefusal refusal,
            boolean credentialReplaced, boolean confined) {

        public Verification {
            Objects.requireNonNull(change, "a verification hands back what it moved");
            if (refusal != null && credentialReplaced) {
                throw new IllegalArgumentException("a Login is refused for one reason");
            }
            if (confined && (refusal != null || credentialReplaced)) {
                throw new IllegalArgumentException("only an accepted Login issues a session");
            }
        }

        /** Whether the state under the lock refused the Login. */
        public boolean refused() {
            return refusal != null || credentialReplaced;
        }

        /**
         * The reason a refused password Login is reported and recorded as: its standing's, or
         * {@code BAD_CREDENTIALS} for a credential replaced since it was compared — at the moment
         * of the decision the submitted password is simply wrong.
         *
         * @throws IllegalStateException if the Login was accepted
         */
        public PasswordRefusalReason passwordRefusal() {
            if (refusal != null) {
                return refusal.passwordReason();
            }
            if (credentialReplaced) {
                return PasswordRefusalReason.BAD_CREDENTIALS;
            }
            throw new IllegalStateException("an accepted Login was refused for nothing");
        }
    }

    private final ScimLoginStateRepository loginStates;
    private final ScimWriteLocks locks;
    private final SessionRevocationService sessions;
    private final LockoutPolicy policy;
    private final AuditTrail audit;
    private final Clock clock;

    /**
     * @param locks takes the User's resource lock before each event decides, so every write of
     *              the login state is serialized here rather than by its caller
     */
    public LoginStateService(
            ScimLoginStateRepository loginStates,
            ScimWriteLocks locks,
            SessionRevocationService sessions,
            LockoutPolicy policy,
            AuditTrail audit,
            Clock clock) {
        this.loginStates = loginStates;
        this.locks = locks;
        this.sessions = sessions;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * A refused password Login or password-change attempt against {@code user}: counts it, and on a
     * newly imposed Lockout audits it and revokes every Session of the User after the commit.
     * Counted on the run stored under the User's lock, so a parallel failure is never lost and
     * only the one that reaches the limit imposes the Lockout.
     *
     * <p>A User with no password has no failure run: there is no password to guess, so an attempt
     * against it lengthens nothing and can never lock out a User who signs in only through Epic.
     * Decided here, on the state read under the lock, beside the Bootstrap Admin's exemption, so
     * no caller decides it on the copy it read before — a password removed in between counts
     * nothing, and nothing is written.
     */
    public LoginStateChange failureCounted(ScimUser user) {
        ScimLoginState before = stateUnderLock(user);
        if (!before.hasPassword()) {
            return LoginStateChange.none(before);
        }
        ScimLoginState after = user.isExemptFromLockout()
                ? before.withFailureCounted()
                : before.withFailureRecorded(policy, clock.instant());
        loginStates.writeChange(user.id(), before, after);
        LoginStateChange change = LoginStateChange.between(before, after);
        if (after.isLocked() && !before.isLocked()) {
            audit.append(new Audited.LockoutSet(user.id(), change.moved()));
            sessions.revokeAllAfterCommit(
                    user.id(), SessionRevocationCause.FAILURE_RUN_LOCKOUT, null);
        }
        return change;
    }

    /**
     * An accepted Login by {@code user}: clears its run, and moves its dormancy basis unless the
     * Login builds a Confined session — a confined session can do nothing but change the password
     * or log out, so it is not use of the account, and counting it would let an imposed credential
     * nobody replaces stay live for as long as somebody keeps logging in with it (ADR 0008).
     *
     * <p>Unless the User's standing, read under its lock, refuses the Login: a Lockout, or a User
     * no longer active or no longer there. The authentication checked both on a read taken before
     * the lock, and a Lockout another attempt imposed, or a deactivation a connector committed,
     * while the password was being compared would otherwise be cleared by the Login it should
     * refuse — lifting a lock no Unlock lifted (ADR 0007). Refused fail closed: nothing is written
     * and the caller is told why, locked first, as the authentication checks it. A lock never
     * reaches {@link ScimLoginState#withFailureRunCleared} by this route.
     *
     * <p>Unless, too, the stored credential is no longer the one the authentication verified: a
     * password replaced while the old one was being compared — by a connector, an Admin, or the
     * User's own change elsewhere — would otherwise sign in, after the replacement had revoked the
     * User's sessions, a Login made with a password that no longer opens the account. It is
     * refused as a wrong password, which at the moment of the decision it is, and nothing is
     * written; the caller counts it as one ({@link Verification#passwordRefusal}). An Epic Login
     * verified no credential of ours, so none it verified can have been replaced.
     *
     * <p>Whether the accepted Login is confined is decided here too, from the same state, and
     * handed back ({@link Verification#confined}) for the session's authorities, so the dormancy
     * basis and the session the Login issues read one answer and cannot disagree.
     */
    public Verification credentialVerified(ScimUser user, VerifiedCredential credential) {
        ScimLoginState before = stateUnderLock(user);
        Optional<StandingRefusal> refused =
                StandingRefusal.of(before, loginStates.isActive(user.id()));
        if (refused.isPresent()) {
            return new Verification(LoginStateChange.none(before), refused.get(), false, false);
        }
        if (!credential.storedIn(before)) {
            return new Verification(LoginStateChange.none(before), null, true, false);
        }
        boolean confined = credential.confines(before);
        ScimLoginState after = before.withFailureRunCleared();
        if (!confined) {
            after = after.withDormancyBasisReset(clock.instant());
        }
        loginStates.writeChange(user.id(), before, after);
        return new Verification(LoginStateChange.between(before, after), null, false, confined);
    }

    /**
     * A self-service change of {@code user}'s password to {@code passwordHash}, its current one
     * having verified: clears the run, completes the change, and moves the dormancy basis — for a
     * User that owed the change it is the first use of the account, since its confined Logins did
     * not move the basis.
     *
     * <p>The completion is written first, and the run and the basis after it, from the state it
     * left.
     *
     * @return what the change moved, and the instant it was completed at, which the credential,
     *     the dormancy basis and the caller's password history all share; no completion and no
     *     path when the User was gone by the write, in which case the basis is not moved
     */
    public LoginStateChange passwordChanged(ScimUser user, String passwordHash) {
        Instant now = clock.instant();
        return changeCompleted(user, passwordHash, now, now);
    }

    /**
     * An Admin Unlock of {@code user}: clears the run and the Lockout, restarts the dormancy basis
     * and, for a User with a password, requires a change — the credential that reached the limit
     * may be the one an attacker was guessing.
     *
     * <p>A User serving no lockout is left exactly as it is stored, its run included, and nothing
     * is written: an Unlock has no effect on an unlocked User. Whether a lock stood is read from
     * the state under the User's lock ({@link LoginStateChange#before}), which the administration
     * path refuses the Unlock on (ADR 0015).
     *
     * @return what the Unlock moved — the run when it held attempts, the lock, the basis and, for
     *     a User with a password, the change-required flag; nothing when no lock stood — and the
     *     state it stored; the lifted lock's cause is the state it read
     */
    public LoginStateChange unlocked(ScimUser user) {
        ScimLoginState before = stateUnderLock(user);
        if (!before.isLocked()) {
            return LoginStateChange.none(before);
        }
        Instant now = clock.instant();
        ScimLoginState after = before.withFailureRunCleared().withDormancyBasisReset(now);
        if (before.hasPassword()) {
            after = after.withPasswordChangeRequired(now);
        }
        loginStates.writeChange(user.id(), before, after);
        return LoginStateChange.between(before, after);
    }

    /**
     * The dormancy job found {@code user} past the lockout window as of {@code now} (ADR 0007):
     * locks it for dormancy, leaving the failure run as it stands — dormancy is not a failed
     * attempt. A User already locked keeps its lock and the cause it was first locked for.
     * Whether the User is due is the job's {@code DormancyVerdict}, not this; the job's audit
     * event and Session revocation follow only a lock this imposed.
     *
     * <p>Decided on the state stored under the User's lock, which the job already holds as part
     * of its batch, so a failure lock the login path committed before it is kept rather than
     * overwritten. The store's own compare-and-set ({@link ScimLoginStateRepository#imposeLock})
     * says whether the lock was imposed, and refuses to overwrite a standing one whatever this
     * read.
     *
     * @return what the lock moved — the lock and its cause, or nothing when the store refused to
     *     overwrite a standing lock
     */
    public LoginStateChange dormancyLocked(ScimUser user, Instant now) {
        ScimLoginState before = stateUnderLock(user);
        ScimLoginState locked = before.withDormancyLock(now);
        boolean imposed = loginStates.imposeLock(
                user.id(), new Lockout(locked.lockedAt(), locked.lockCause()));
        return imposed ? LoginStateChange.between(before, locked) : LoginStateChange.none(before);
    }

    /**
     * A User was created — by a connector, or as the seeded Bootstrap Admin: a credential somebody
     * else chose (a connector's, or the deployment's configured one) is known outside the User,
     * so a User created with one owes a change as of its creation. A User created without one owes
     * nothing; the flag arrives with its first password.
     */
    @Override
    public LoginStateChange created(String passwordHash, Instant now) {
        ScimLoginState created = ScimLoginState.of(passwordHash);
        return LoginStateChange.between(created,
                created.hasPassword() ? created.withPasswordChangeRequired(now) : created);
    }

    /**
     * A connector replaced {@code stored} — by PUT or by PATCH, one rule for both: the credential
     * becomes the written one, and the rest is decided here.
     *
     * <ul>
     *   <li>A password the connector set owes a change as of {@code now}, for the reason a
     *       created one does.
     *   <li>A Reactivation — {@code active} from false to true — restarts the dormancy basis at
     *       {@code now}, so the next dormancy run does not lock again a User the directory has
     *       just brought back, and, for a User that holds a credential after the write, requires
     *       a change as of {@code now}: a credential that sat unused across a deactivation is not
     *       trusted on return. Re-dated when already set, so the flag records the Reactivation
     *       that imposed it. A Reactivation lifts no lock; only an Unlock does.
     *   <li>{@code true} over {@code true} is no Reactivation and resets nothing, so a connector
     *       re-asserting {@code active} on every sync cannot hold a dormant User open.
     * </ul>
     *
     * <p>No directory write clears the flag — only the User's own completed change does — and the
     * failure run and the lock are carried as read, since the replacement does not store them.
     */
    @Override
    public LoginStateChange afterReplacement(
            ScimUser stored, ScimUserProfile profile, Credential credential, Instant now) {
        ScimLoginState after = stored.login().withCredential(credential.passwordHash());
        if (credential.connectorSet()) {
            after = after.withPasswordChangeRequired(now);
        }
        if (!stored.profile().active() && profile.active()) {
            after = after.withDormancyBasisReset(now);
            if (after.hasPassword()) {
                after = after.withPasswordChangeRequired(now);
            }
        }
        return LoginStateChange.between(stored.login(), after);
    }

    /**
     * The development seed reset its dormant fixture: the fixture password back with no change
     * owed, the run and any lock cleared, and the dormancy basis {@code dormantFor} before
     * {@code now}, so the development profile's startup run of the job locks it again.
     */
    @Override
    public LoginStateChange dormantFixtureReset(
            ScimUser fixture, String passwordHash, Instant now, Duration dormantFor) {
        return changeCompleted(fixture, passwordHash, now, now.minus(dormantFor));
    }

    /**
     * Completes a change of {@code user}'s password to {@code passwordHash} as of
     * {@code completedAt}, then clears its run and moves its dormancy basis to {@code basis}: the
     * shared transition of a self-service change and the dormant fixture's reset.
     *
     * <p>Takes the User's lock. Whatever it read, the state the run and the basis are written from
     * is the one the completion left, so the change cannot write back the flag the completion
     * cleared, and a User gone by the completion has nothing else written either.
     *
     * @return what the change moved from the state read under the lock, and {@code completedAt};
     *     no completion and no path when the User was gone by the completion and nothing was
     *     written
     */
    private LoginStateChange changeCompleted(
            ScimUser user, String passwordHash, Instant completedAt, Instant basis) {
        ScimLoginState before = stateUnderLock(user);
        return loginStates
                .completePasswordChange(user.id(), passwordHash, completedAt)
                .map(ScimUser::login)
                .map(completed -> {
                    ScimLoginState after =
                            completed.withFailureRunCleared().withDormancyBasisReset(basis);
                    loginStates.writeChange(user.id(), completed, after);
                    return new LoginStateChange(before, after, completedAt);
                })
                .orElseGet(() -> LoginStateChange.none(before));
    }

    /**
     * Takes {@code user}'s resource lock and reads its login state as stored under it: the one
     * state every event decides from. The caller's copy is used only for a User gone by the read,
     * whose writes then match no row — the outcome a deletion committed just after the event
     * would have had.
     */
    private ScimLoginState stateUnderLock(ScimUser user) {
        locks.lock(List.of(user.id()));
        return loginStates.findLoginState(user.id()).orElse(user.login());
    }
}
