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
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The login-state module: the one owner of a User's Failure run and lock, its dormancy basis and
 * its change-required flag. A caller reports what happened to the User a stable id names — a
 * password Login, an Epic Login, a self-service change's wrong current password, a password
 * changed, an Unlock, a dormancy lock, a directory write — and this decides the resulting state
 * and writes what it changed, or hands the state back to be stored with the write it belongs to.
 * No caller decides those fields itself.
 *
 * <p>The rules, event by event:
 *
 * <ul>
 *   <li><b>Password Login</b> ({@link #passwordLogin}) — decided and counted under the one lock.
 *       A Login the authentication refused is counted for the reason it was refused for: the run
 *       lengthens, and reaching the {@link LockoutPolicy}'s limit imposes a Lockout for failures
 *       as of now — unless it was refused for a Lockout ({@code ACCOUNT_LOCKED}), which counts
 *       nothing whatever is stored by the time it is counted: the attempt was made against the
 *       Lockout, so an Unlock committed in between does not make it the first failure of the run
 *       the Unlock started. A Login the authentication accepted is decided as a verified
 *       credential is (below), and a refusal there is counted as that refusal, for the reason it
 *       is reported as.
 *   <li><b>Password-change failure</b> ({@link #passwordChangeFailed}) — a self-service change's
 *       wrong current password: counted as a refused Login is.
 *   <li><b>Credential verified</b> — what an accepted Login is decided as, by either route: the
 *       run is cleared, and the dormancy basis moves unless the Login builds a Confined session
 *       (ADR 0008). A User found locked or deactivated under its lock is refused instead, and
 *       nothing is written: a Login never lifts a Lockout (ADR 0007). So is a password Login
 *       whose verified credential is no longer the stored one, as a wrong password. An Epic
 *       Login's refusal ({@link #epicLogin}) counts toward no failure run (D12).
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
 * <p>Every failure counted is counted under the same two exemptions: the Bootstrap Admin's
 * failures are counted and never lock, read off its own reservation marker
 * ({@code /docs/adr/0007-lockout-lifted-only-by-admin-unlock.md}), and a User with no password has
 * no run, so nothing is counted against it.
 *
 * <h2>A User gone under its lock</h2>
 *
 * <p>Every event names its User by stable id, and this reads the User — its login state, and the
 * reservation marker the Bootstrap Admin's exemption is read off — under the User's lock. A User
 * missing there is <b>gone</b>: the event answers empty, and nothing is written, counted or
 * audited. A deletion is final, so no state the caller read before could decide anything else.
 * What gone means for the caller's own record is the caller's to say: a password Login is refused
 * against nobody ({@code UNKNOWN_ACCOUNT}), an Epic Login is refused as a deactivated account's,
 * an Unlock as an unknown identity, and the dormancy job skips the User. So the outcome is the
 * same however early the deletion landed — before the caller's own read, or between it and the
 * lock.
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
 * ({@link PasswordLoginDecision#confined}, {@link Verification#confined}). A password Login by a
 * User whose flag is set is confined; an Epic Login never is, because it presented no password of
 * ours (D20), so it moves the basis while the flag stays set — only a successful password change
 * clears it.
 *
 * <h2>What a newly imposed Lockout implies</h2>
 *
 * <p>The transition into a Lockout — and only the transition — is this module's to carry out, for
 * either cause, so a cause added later cannot forget either half. In this order, after the new
 * state is written, so a caller's own record of the event, made once this returns, lands last:
 *
 * <ul>
 *   <li>it is audited: a failure run's as {@code LOCKOUT_SET}, a dormancy lock's as
 *       {@code DORMANCY_LOCKOUT}, each naming what the lock moved;
 *   <li>every Session of the User ends, through the Session revocation module
 *       ({@link SessionRevocationService}) under {@link SessionRevocationCause#FAILURE_RUN_LOCKOUT}
 *       or {@link SessionRevocationCause#DORMANCY_LOCKOUT}: after the transaction commits,
 *       because Redis is not in the transaction
 *       ({@code /docs/adr/0002-revoke-sessions-after-commit.md}), and audited under that cause.
 * </ul>
 *
 * <p>How each append fails is its event's ({@link AuditTrail}; ADR 0004): {@code LOCKOUT_SET} is
 * fail-open, as everything on the failure path is, and a session store that fails is recorded and
 * alerted without changing the path's bare refusal; {@code DORMANCY_LOCKOUT} is fail-closed, so a
 * dormancy run that cannot record its lock rolls back with it. An Unlock's and a password change's
 * own records are their callers', because each is a fail-closed part of the caller's operation.
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
 * ({@link LoginStateChange#moved}) is the columns that differ, read by the rule
 * {@link ScimLoginStateRepository#writeChange} writes by, so they are exactly what was written —
 * and every audit row a transition produces names its changed paths from them, never from a list
 * of its own: a refused Login that lengthened the run names {@code failedLoginAttempts}, and one
 * that counted nothing — a locked User's — names nothing ({@code /docs/domain/authentication.md}).
 * A transition that moved nothing hands back no path, and a completed password change also says
 * when it was completed, which is how its row names the credential. The directory's events answer
 * in the same shape, which lives beside their port in {@code scim.domain} so the port can name
 * it: a create or a replacement hands back the change whose {@link LoginStateChange#after} its own
 * row write stores, and the dormant fixture's reset the change it wrote. None of the three records
 * a login-state row.
 *
 * <h2>Serialization</h2>
 *
 * <p>Every event that writes the login state is serialized here, so no caller decides whether to
 * lock: each takes the User's resource lock through {@link ScimWriteLocks} and decides from the
 * User read under it, never from a copy its caller read before. Two events on one User therefore
 * end as one serial order of them would:
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
 * <p>The login state and {@code active} are re-read by statements of their own
 * ({@link ScimLoginStateRepository#findLoginState}, {@link ScimLoginStateRepository#isActive})
 * because the lock cannot always come first: a Login must find its User by name before it can
 * name the row, so the transaction may already hold an older copy, which an ordinary lookup by id
 * would hand back. Only the reservation marker is read off the User itself, because no write
 * changes it. A caller already holding the lock — the password change, the dormancy job's batch in
 * canonical order — loses nothing: taking a row the transaction holds waits for nobody.
 * {@code LoginStateSerializationIntegrationTests} proves the guarantee against a real database;
 * the in-memory stores cannot.
 *
 * <p>Each event joins its caller's transaction rather than opening one: the caller decides what
 * commits with it, and the lock is held until it does. Counting is still triggered explicitly
 * from the Login and password-change paths
 * ({@code /docs/adr/0001-count-attempts-and-complete-every-login-on-the-login-path.md}); nothing
 * here listens for events.
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
        static VerifiedPassword passwordHash(String passwordHash) {
            return new VerifiedPassword(passwordHash);
        }

        /** An Epic Login's: Epic checked the credential, not us. */
        static VerifiedCredential epic() {
            return new VerifiedByEpic();
        }
    }

    /**
     * What a password Login's authentication answered, which the Login is decided and counted on
     * under the User's lock ({@link #passwordLogin}): the stored credential it verified
     * ({@link VerifiedCredential#passwordHash}), or its refusal and the reason for it
     * ({@link #refused}).
     */
    public sealed interface PasswordAnswer {

        /** The authentication refused the Login for {@code reason}. */
        static PasswordAnswer refused(PasswordRefusalReason reason) {
            return new RefusedPassword(reason);
        }
    }

    /** A password Login the authentication refused, and the reason it refused it for. */
    public record RefusedPassword(PasswordRefusalReason reason) implements PasswordAnswer {

        public RefusedPassword {
            Objects.requireNonNull(reason, "a refusal has a reason");
        }
    }

    /**
     * A password Login's verified credential ({@link VerifiedCredential#passwordHash}). Held only
     * for the decision, never in the session, and never printed: the hash it carries is read by
     * nothing outside this module.
     */
    public static final class VerifiedPassword implements VerifiedCredential, PasswordAnswer {

        private final String passwordHash;

        private VerifiedPassword(String passwordHash) {
            this.passwordHash = Objects.requireNonNull(
                    passwordHash, "a password Login verified a stored credential");
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
        /** The User is no longer active. */
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
     * state refused the Login, why, and a change that moved nothing. An Epic Login's decision
     * ({@link #epicLogin}).
     *
     * @param change              what the Login moved: an accepted Login's cleared run and basis,
     *     and nothing for a refused one
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
    }

    /**
     * What a password Login came to, decided and counted under the User's lock
     * ({@link #passwordLogin}).
     *
     * @param change   what the Login moved: an accepted Login's cleared run and basis, or the
     *     failure a refused one counted — nothing for a refusal for a Lockout
     * @param refusal  the reason the Login is refused, reported and recorded as, or {@code null}
     *     when it was accepted
     * @param confined whether the accepted Login builds a Confined session; {@code false} for a
     *     refused one
     */
    public record PasswordLoginDecision(
            LoginStateChange change, PasswordRefusalReason refusal, boolean confined) {

        public PasswordLoginDecision {
            Objects.requireNonNull(change, "a decision hands back what it moved");
            if (confined && refusal != null) {
                throw new IllegalArgumentException("only an accepted Login issues a session");
            }
        }

        /** Whether the Login was refused. */
        public boolean refused() {
            return refusal != null;
        }
    }

    /**
     * A User as read under its lock: its stable id, whether its reservation marker exempts it from
     * Lockout, and its login state as stored.
     */
    private record Locked(UUID id, boolean exemptFromLockout, ScimLoginState state) {
    }

    private final ScimLoginStateRepository loginStates;
    private final ScimUserRepository users;
    private final ScimWriteLocks locks;
    private final SessionRevocationService sessions;
    private final LockoutPolicy policy;
    private final AuditTrail audit;
    private final Clock clock;

    /**
     * @param users the directory's Users, read under the lock for the reservation marker alone
     * @param locks takes the User's resource lock before each event decides, so every write of
     *              the login state is serialized here rather than by its caller
     */
    public LoginStateService(
            ScimLoginStateRepository loginStates,
            ScimUserRepository users,
            ScimWriteLocks locks,
            SessionRevocationService sessions,
            LockoutPolicy policy,
            AuditTrail audit,
            Clock clock) {
        this.loginStates = loginStates;
        this.users = users;
        this.locks = locks;
        this.sessions = sessions;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * A password Login of the User {@code userId}, as its authentication {@code answered}:
     * decided, and its refusal counted, on the one state read under the User's lock, which is
     * taken once — the refusal cannot be counted on a state it was not decided from, and there is
     * no moment between the two in which a second writer could land. The caller's transaction
     * therefore holds both, and commits them together.
     *
     * <ul>
     *   <li><b>Refused by the authentication</b> ({@link PasswordAnswer#refused}): counted for its
     *       reason. A Login refused {@code ACCOUNT_LOCKED} counts nothing, whatever the state read
     *       under the lock now holds, and nothing is written. The authentication's refusal was
     *       decided on its read, before this runs, and an Admin Unlock can commit in between,
     *       leaving an unlocked User with a cleared run. Counting the attempt then would make one
     *       refused for the Lockout the first failure of the run the Unlock started, when an
     *       attempt made while a Lockout holds neither counts nor deepens it (ADR 0007). So the
     *       decision is the refusal's, not the re-read state's. Every other reason is counted.
     *   <li><b>Accepted by the authentication</b> ({@link VerifiedCredential#passwordHash}): its
     *       run cleared and its dormancy basis moved unless the session is confined, as
     *       {@link #epicLogin} decides an Epic Login — unless the standing under the lock refuses
     *       it ({@code ACCOUNT_LOCKED}, {@code ACCOUNT_DISABLED}), or the stored credential is no
     *       longer the one the authentication verified: a password replaced while the old one was
     *       being compared — by a connector, an Admin, or the User's own change elsewhere — would
     *       otherwise sign in, after the replacement had revoked the User's sessions, a Login made
     *       with a password that no longer opens the account. That Login is refused as the wrong
     *       password it is at the moment of the decision, {@code BAD_CREDENTIALS}. A refusal here
     *       is counted as the authentication's refusal for the same reason is: a replaced
     *       credential's and a deactivation's lengthen the run, and may impose the Lockout, while
     *       a Lockout's counts nothing.
     * </ul>
     *
     * @return the decision — what was moved, why the Login was refused and whether an accepted
     *     one is confined — or empty when the User is gone under its lock, with nothing written,
     *     counted or audited
     */
    public Optional<PasswordLoginDecision> passwordLogin(UUID userId, PasswordAnswer answered) {
        return underLock(userId).map(user -> switch (answered) {
            case RefusedPassword refused ->
                    new PasswordLoginDecision(countedFor(user, refused.reason()), refused.reason(),
                            false);
            case VerifiedPassword credential -> passwordVerified(user, credential);
        });
    }

    /** An accepted password authentication decided under the lock, and its refusal counted. */
    private PasswordLoginDecision passwordVerified(Locked user, VerifiedPassword credential) {
        Verification decided = decided(user, credential);
        if (!decided.refused()) {
            return new PasswordLoginDecision(decided.change(), null, decided.confined());
        }
        PasswordRefusalReason reason = decided.refusal() != null
                ? decided.refusal().passwordReason()
                : PasswordRefusalReason.BAD_CREDENTIALS;
        return new PasswordLoginDecision(countedFor(user, reason), reason, false);
    }

    /**
     * An Epic Login of the User {@code userId} (D12): its run cleared, and its dormancy basis
     * moved — an Epic Login is never confined, because it presented no password of ours (D20).
     *
     * <p>Unless the User's standing, read under its lock, refuses the Login: a Lockout, or a User
     * no longer active. The Login's lookup read both before the lock, and a Lockout or a
     * deactivation committed since would otherwise be cleared by the Login it should refuse —
     * lifting a lock no Unlock lifted (ADR 0007). Refused fail closed: nothing is written, and its
     * refusal counts toward no failure run. Locked first, as the authentication checks it.
     *
     * @return the decision, or empty when the User is gone under its lock, with nothing written
     */
    public Optional<Verification> epicLogin(UUID userId) {
        return underLock(userId).map(user -> decided(user, VerifiedCredential.epic()));
    }

    /**
     * The decision on a verified {@code credential}, from {@code user}'s state read under its lock:
     * refused on the standing first, then on a replaced credential, and otherwise accepted, its
     * run cleared and its basis moved unless it is confined.
     */
    private Verification decided(Locked user, VerifiedCredential credential) {
        ScimLoginState before = user.state();
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
     * A self-service password change of the User {@code userId} refused for a wrong current
     * password: counted exactly as a refused password Login is, so the change is not a second
     * guessing surface beside Login. A self-service change has no reason to tell it from, so
     * every one is counted.
     *
     * @return what counting it moved, or empty when the User is gone under its lock
     */
    public Optional<LoginStateChange> passwordChangeFailed(UUID userId) {
        return underLock(userId).map(this::counted);
    }

    /**
     * {@code user}'s password Login refused for {@code reason}: nothing for a refusal for a
     * Lockout, as {@link #passwordLogin} says why, and otherwise counted.
     */
    private LoginStateChange countedFor(Locked user, PasswordRefusalReason reason) {
        if (reason == PasswordRefusalReason.ACCOUNT_LOCKED) {
            return LoginStateChange.none(user.state());
        }
        return counted(user);
    }

    /**
     * {@code user}'s failure counted on the run stored under its lock, so a parallel failure is
     * never lost and only the one that reaches the limit imposes the Lockout — whose consequences
     * follow ({@link #written}). A User found locked there is left as it is.
     *
     * <p>A User with no password has no failure run: there is no password to guess, so an attempt
     * against it lengthens nothing and can never lock out a User who signs in only through Epic.
     * Decided here, on the state read under the lock, beside the Bootstrap Admin's exemption, so
     * no caller decides it on the copy it read before — a password removed in between counts
     * nothing, and nothing is written.
     */
    private LoginStateChange counted(Locked user) {
        ScimLoginState before = user.state();
        if (!before.hasPassword()) {
            return LoginStateChange.none(before);
        }
        ScimLoginState after = user.exemptFromLockout()
                ? before.withFailureCounted()
                : before.withFailureRecorded(policy, clock.instant());
        loginStates.writeChange(user.id(), before, after);
        return written(user.id(), LoginStateChange.between(before, after));
    }

    /**
     * A self-service change of the User {@code userId}'s password to {@code passwordHash}, its
     * current one having verified: clears the run, completes the change, and moves the dormancy
     * basis — for a User that owed the change it is the first use of the account, since its
     * confined Logins did not move the basis.
     *
     * <p>The completion is written first, and the run and the basis after it, from the state it
     * left.
     *
     * @return what the change moved, and the instant it was completed at, which the credential,
     *     the dormancy basis and the caller's password history all share; empty when the User was
     *     gone, under its lock or by the write, in which case nothing else is written
     */
    public Optional<LoginStateChange> passwordChanged(UUID userId, String passwordHash) {
        Instant now = clock.instant();
        return changeCompleted(userId, passwordHash, now, now);
    }

    /**
     * An Admin Unlock of the User {@code userId}: clears the run and the Lockout, restarts the
     * dormancy basis and, for a User with a password, requires a change — the credential that
     * reached the limit may be the one an attacker was guessing.
     *
     * <p>A User serving no lockout is left exactly as it is stored, its run included, and nothing
     * is written: an Unlock has no effect on an unlocked User. Whether a lock stood is read from
     * the state under the User's lock ({@link LoginStateChange#before}), which the administration
     * path refuses the Unlock on (ADR 0015).
     *
     * @return what the Unlock moved — the run when it held attempts, the lock, the basis and, for
     *     a User with a password, the change-required flag; nothing when no lock stood — and the
     *     state it stored, the lifted lock's cause being the state it read; empty when the User is
     *     gone under its lock
     */
    public Optional<LoginStateChange> unlocked(UUID userId) {
        return underLock(userId).map(user -> {
            ScimLoginState before = user.state();
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
        });
    }

    /**
     * The dormancy job found the User {@code userId} past the lockout window as of {@code now}
     * (ADR 0007): locks it for dormancy, leaving the failure run as it stands — dormancy is not a
     * failed attempt — and carries out what the new Lockout implies ({@link #written}). A User
     * already locked keeps its lock and the cause it was first locked for, and nothing follows.
     * Whether the User is due is the job's {@code DormancyVerdict}, not this.
     *
     * <p>Decided on the state stored under the User's lock, which the job already holds as part
     * of its batch, so a failure lock the login path committed before it is kept rather than
     * overwritten. The store's own compare-and-set ({@link ScimLoginStateRepository#imposeLock})
     * says whether the lock was imposed, and refuses to overwrite a standing one whatever this
     * read.
     *
     * @return what the lock moved — the lock and its cause, or nothing when the store refused to
     *     overwrite a standing lock; empty when the User is gone under its lock
     */
    public Optional<LoginStateChange> dormancyLocked(UUID userId, Instant now) {
        return underLock(userId).map(user -> {
            ScimLoginState before = user.state();
            ScimLoginState locked = before.withDormancyLock(now);
            if (!loginStates.imposeLock(
                    user.id(), new Lockout(locked.lockedAt(), locked.lockCause()))) {
                return LoginStateChange.none(before);
            }
            return written(user.id(), LoginStateChange.between(before, locked));
        });
    }

    /**
     * {@code change}, just written for the User {@code userId}, with what it implies when it
     * imposed a Lockout — only the transition into one: the Lockout audited under its cause, and
     * every Session of the User revoked under that cause once the transaction commits. One
     * exhaustive switch pairs each cause with both, so a cause added later cannot be left out of
     * either.
     */
    private LoginStateChange written(UUID userId, LoginStateChange change) {
        if (!change.after().isLocked() || change.before().isLocked()) {
            return change;
        }
        LockoutConsequences consequences = switch (change.after().lockCause()) {
            case FAILURES -> new LockoutConsequences(
                    new Audited.LockoutSet(userId, change.moved()),
                    SessionRevocationCause.FAILURE_RUN_LOCKOUT);
            case DORMANCY -> new LockoutConsequences(
                    new Audited.DormancyLockout(userId, change.moved()),
                    SessionRevocationCause.DORMANCY_LOCKOUT);
        };
        audit.append(consequences.event());
        sessions.revokeAllAfterCommit(userId, consequences.revocation(), null);
        return change;
    }

    /** What a newly imposed Lockout of one cause implies: its audit event and its revocation. */
    private record LockoutConsequences(Audited event, SessionRevocationCause revocation) {
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
     * {@code now}, so the development profile's startup run of the job locks it again. A fixture
     * gone by the lock — which the seed has just found or created — has nothing written, and the
     * change is the seed's own copy, unmoved.
     */
    @Override
    public LoginStateChange dormantFixtureReset(
            ScimUser fixture, String passwordHash, Instant now, Duration dormantFor) {
        return changeCompleted(fixture.id(), passwordHash, now, now.minus(dormantFor))
                .orElseGet(() -> LoginStateChange.none(fixture.login()));
    }

    /**
     * Completes a change of the User {@code userId}'s password to {@code passwordHash} as of
     * {@code completedAt}, then clears its run and moves its dormancy basis to {@code basis}: the
     * shared transition of a self-service change and the dormant fixture's reset.
     *
     * <p>Takes the User's lock. Whatever it read, the state the run and the basis are written from
     * is the one the completion left, so the change cannot write back the flag the completion
     * cleared, and a User gone by the completion has nothing else written either.
     *
     * @return what the change moved from the state read under the lock, and {@code completedAt};
     *     empty when the User was gone, under the lock or by the completion, and nothing was
     *     written
     */
    private Optional<LoginStateChange> changeCompleted(
            UUID userId, String passwordHash, Instant completedAt, Instant basis) {
        return underLock(userId).flatMap(user -> loginStates
                .completePasswordChange(userId, passwordHash, completedAt)
                .map(ScimUser::login)
                .map(completed -> {
                    ScimLoginState after =
                            completed.withFailureRunCleared().withDormancyBasisReset(basis);
                    loginStates.writeChange(userId, completed, after);
                    return new LoginStateChange(user.state(), after, completedAt);
                }));
    }

    /**
     * Takes the User {@code userId}'s resource lock and reads it under the lock: its login state,
     * as stored — the one state every event decides from — and its reservation marker. Empty when
     * the User is gone by the read.
     */
    private Optional<Locked> underLock(UUID userId) {
        locks.lock(List.of(userId));
        return loginStates.findLoginState(userId).flatMap(state -> users.findById(userId)
                .map(user -> new Locked(userId, user.isExemptFromLockout(), state)));
    }
}
