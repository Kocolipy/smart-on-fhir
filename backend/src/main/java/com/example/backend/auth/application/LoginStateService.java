package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLockCause;
import com.example.backend.audit.domain.AuditLoginStateAttribute;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
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
import org.springframework.security.core.GrantedAuthority;
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
 *   <li><b>Password Login</b> ({@link #passwordLogin}, {@link #passwordLoginRefused}) — decided
 *       and counted under the one lock. A Login the authentication refused is counted for the
 *       reason it was refused for: the run lengthens, and reaching the {@link LockoutPolicy}'s
 *       limit imposes a Lockout for failures as of now — unless it was refused for a Lockout
 *       ({@code ACCOUNT_LOCKED}), which counts nothing whatever is stored by the time it is
 *       counted: the attempt was made against the Lockout, so an Unlock committed in between does
 *       not make it the first failure of the run the Unlock started. A Login the authentication
 *       accepted is decided as an accepted Login is (below), and a refusal there is counted as
 *       that refusal, for the reason it is reported as.
 *   <li><b>Password change</b> ({@link #passwordChange}, {@link #passwordChangeRefused}) — a
 *       self-service change, its current password compared and its new one decided on by the
 *       caller before the lock, in no transaction, as a password Login's comparison is; confirmed
 *       and recorded here under the one lock. A locked or deactivated User is refused on its
 *       standing and counts nothing; a wrong current password — or a credential replaced since
 *       the comparison — is counted as a refused Login is; a new password the policy refused is
 *       refused for its rule.
 *   <li><b>Login accepted</b> — what an accepted Login is decided as, by either route: the run is
 *       cleared, the dormancy basis moves unless the Login builds a Confined session (ADR 0007),
 *       and the authority the session is issued with is read. A User found locked or deactivated
 *       under its lock is refused instead, and nothing is written: a Login never lifts a Lockout
 *       (ADR 0007). So is a password Login whose verified credential is no longer the stored one,
 *       as a wrong password. An Epic Login's refusal ({@link #epicLogin}) counts toward no failure
 *       run (D12).
 *   <li><b>Password changed</b> — a change confirmed under the lock: the run is cleared, the
 *       change is completed (the new credential stored and the flag cleared in one write), and
 *       the dormancy basis moves.
 *   <li><b>Unlocked</b> ({@link #unlocked}) — an Admin Unlock: the run and the Lockout are
 *       cleared, the dormancy basis restarts (ADR 0007) and, for a User with a password, a change
 *       is required. A User serving no lockout is left exactly as stored and nothing is written;
 *       the administration path refuses that Unlock on the verdict.
 *   <li><b>Dormancy-locked</b> ({@link #dormancyLocked}) — the dormancy job found a User due: it
 *       is locked for dormancy, the run untouched; an existing lock and its cause are never
 *       overwritten (ADR 0007).
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
 * an Unlock as an unknown identity, a password change as a stale read is, unrecorded, and the
 * dormancy job skips the User. So the outcome is the
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
 * <h2>Every event's verdict</h2>
 *
 * <p>Every event its callers report answers with a typed verdict, and the caller decides nothing
 * from the raw change it carries:
 *
 * <ul>
 *   <li>a Login's {@link LoginVerdict}: accepted with the authority its session is issued with,
 *       or refused with the reason it is reported and recorded as and what counting it moved —
 *       which the Login records;
 *   <li>an Unlock's {@link UnlockVerdict}: lifted, or not locked — which the administration path
 *       answers, or refuses;
 *   <li>a dormancy lock's {@link DormancyLockVerdict}: imposed, or already locked — which the job
 *       logs and counts, or skips;
 *   <li>a password change's {@link PasswordChangeVerdict}: changed, refused by the check, or
 *       refused by the policy — which the change answers and logs.
 * </ul>
 *
 * <h2>The Login's authority</h2>
 *
 * <p>The authority is read here, under the User's lock and in the Login's decision transaction,
 * from the same moment as the standing that accepted the Login
 * ({@link LoginIdentityService#sessionAuthorities}) — never from the authentication's read before
 * the lock, which a Group membership or Group Permissions change committed while the password was
 * compared would leave behind, after that change's revocation had already found no session to end.
 *
 * <p>Whether a Login builds a Confined session is decided here too, once, on the same state, and
 * both of its consequences read that one answer: the dormancy basis this module moves, and the
 * authority the verdict carries ({@link LoginVerdict.Accepted#confined}). A password Login by a
 * User whose flag is set is confined; an Epic Login never is, because it presented no password of
 * ours (D20), so it moves the basis while the flag stays set — only a successful password change
 * clears it.
 *
 * <h2>What this records</h2>
 *
 * <p>The records of every event but a Login's are this module's, written once the state is: an
 * Unlock's {@code LOCKOUT_LIFT} and, when it required a change, its
 * {@code PASSWORD_CHANGE_REQUIRE}; a password change's {@code PASSWORD_CHANGE}, accepted or
 * refused, and an accepted change's revocation of every Session after the commit, under
 * {@link SessionRevocationCause#PASSWORD_CHANGED}; and what a newly imposed Lockout implies,
 * below. A Login's own {@code LOGIN_SUCCESS} or {@code LOGIN_FAILURE} is its caller's, recorded
 * from the verdict, since a refused Login's record is shared with refusals decided before any
 * event reaches this module.
 *
 * <h2>What a newly imposed Lockout implies</h2>
 *
 * <p>The transition into a Lockout — and only the transition — is this module's to carry out, for
 * either cause, so a cause added later cannot forget either half. In this order, after the new
 * state is written, so the event's own record — a Login's, made by its caller once this returns,
 * or a refused password change's, made here — lands last:
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
 * dormancy run that cannot record its lock rolls back with it. An Unlock's two records and an
 * accepted password change's are fail-closed parts of their operation, so one the trail cannot
 * write rolls it back; a refused change's is fail-open, as every refusal is.
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
 *       with a lock lifted that the Unlock did not see;
 *   <li>a password change racing a password Login with the same password ends either
 *       signed-in-then-changed or changed-then-refused, the Login then counted as the wrong
 *       password it is by then — each confirms that the credential it compared is still stored.
 * </ul>
 *
 * <p>The login state and {@code active} are re-read by statements of their own
 * ({@link ScimLoginStateRepository#findLoginState}, {@link ScimLoginStateRepository#isActive})
 * because the lock cannot always come first: a Login must find its User by name before it can
 * name the row, so the transaction may already hold an older copy, which an ordinary lookup by id
 * would hand back. Only the reservation marker is read off the User itself, because no write
 * changes it. A caller already holding the lock — the dormancy job's batch in canonical order —
 * loses nothing: taking a row the transaction holds waits for nobody. A password change takes it
 * here once, and only after its Argon2id comparisons are done.
 * {@code LoginStateSerializationIntegrationTests} proves the guarantee against a real database;
 * the in-memory stores cannot.
 *
 * <p>Each event joins its caller's transaction rather than opening one: the caller decides what
 * commits with it, and the lock is held until it does. An event asked for outside a transaction
 * is refused by the lock itself, before anything is read or written: {@link ScimWriteLocks}
 * requires its caller's transaction, since a row lock on an auto-commit connection would end with
 * its statement and every guarantee above with it. A password Login and a password change each
 * open theirs only once their comparison has answered, so neither holds the User's lock — or a
 * pooled connection — across Argon2id. Counting is still triggered explicitly from the Login and
 * password-change paths
 * ({@code /docs/adr/0001-count-attempts-and-complete-every-login-on-the-login-path.md}); nothing
 * here listens for events.
 */
@Service
public class LoginStateService implements ScimUserLoginState {

    /**
     * The stored credential a password Login's password was compared against and matched, which
     * the Login is decided on under the User's lock: it stands only while that credential is still
     * the one stored. A salted hash differs on every write, so a credential replaced since the
     * comparison — even by the same password — is told apart from it.
     *
     * <p>Held only for the decision, never in the session, and never printed: the hash it carries
     * is read by nothing outside this module.
     */
    public static final class VerifiedPassword {

        private final String passwordHash;

        private VerifiedPassword(String passwordHash) {
            this.passwordHash = Objects.requireNonNull(
                    passwordHash, "a password Login verified a stored credential");
        }

        /** The stored credential {@code passwordHash} the submitted password matched. */
        public static VerifiedPassword of(String passwordHash) {
            return new VerifiedPassword(passwordHash);
        }

        /** Whether {@code state}, read under the User's lock, still holds this credential. */
        private boolean storedIn(ScimLoginState state) {
            return passwordHash.equals(state.passwordHash());
        }

        @Override
        public String toString() {
            return "VerifiedPassword[redacted]";
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
     * What a Login came to, decided once on the state read under the User's lock: accepted, with
     * the authority its session is issued with, or refused, for the reason the Login is reported
     * and recorded as. Either way it hands back what it moved.
     *
     * @param <R> the refusal reasons of the Login's method: {@link PasswordRefusalReason} for a
     *     password Login, {@link EpicLoginFailureReason} for an Epic one
     */
    public sealed interface LoginVerdict<R> {

        /**
         * What the Login moved: an accepted Login's cleared run and basis, or the failure a
         * refused password Login counted — nothing for a refusal that counts nothing.
         */
        LoginStateChange change();

        /**
         * An accepted Login.
         *
         * @param confined    whether its session is Confined, the answer that also left the
         *     dormancy basis where it was
         * @param authorities what its session is issued with, read under the User's lock in the
         *     Login's decision: {@code PASSWORD_CHANGE_REQUIRED} alone for a confined one, and
         *     otherwise baseline access and what the User's Groups confer
         *     ({@link LoginIdentityService#sessionAuthorities})
         */
        record Accepted<R>(LoginStateChange change, boolean confined,
                List<GrantedAuthority> authorities) implements LoginVerdict<R> {

            public Accepted {
                requireMoved(change);
                authorities = List.copyOf(authorities);
            }
        }

        /** A refused Login, and the reason it is reported and recorded as. */
        record Refused<R>(LoginStateChange change, R reason) implements LoginVerdict<R> {

            public Refused {
                requireMoved(change);
                Objects.requireNonNull(reason, "a refusal has a reason");
            }
        }
    }

    /**
     * What an Admin Unlock came to, decided on the state read under the User's lock: a Lockout
     * lifted, already recorded, or none standing to lift. Either way it hands back what it moved
     * and the state it left stored, which the administration path answers with.
     */
    public sealed interface UnlockVerdict {

        /** What the Unlock moved: the lifted lock and all it cleared, or nothing. */
        LoginStateChange change();

        /**
         * A Lockout stood and is lifted, and its {@code LOCKOUT_LIFT} — with its
         * {@code PASSWORD_CHANGE_REQUIRE} when it required a change — is recorded.
         */
        record Lifted(LoginStateChange change) implements UnlockVerdict {

            public Lifted {
                requireMoved(change);
            }
        }

        /**
         * No Lockout stood: nothing is written or recorded, and the administration path refuses
         * the Unlock ({@code NOT_LOCKED}).
         */
        record NotLocked(LoginStateChange change) implements UnlockVerdict {

            public NotLocked {
                requireMoved(change);
            }
        }
    }

    /**
     * What the dormancy job's lock of a due User came to, decided by the store's compare-and-set
     * under the User's lock: a Lockout imposed, with what that implies already carried out, or one
     * already standing, kept with its cause and nothing following.
     */
    public sealed interface DormancyLockVerdict {

        /** What the lock moved: the lock and its cause, or nothing. */
        LoginStateChange change();

        /** A dormancy Lockout imposed, audited and its Sessions' revocation queued. */
        record Imposed(LoginStateChange change) implements DormancyLockVerdict {

            public Imposed {
                requireMoved(change);
            }
        }

        /** A lock already stood, of either cause, and is kept: nothing is written or recorded. */
        record AlreadyLocked(LoginStateChange change) implements DormancyLockVerdict {

            public AlreadyLocked {
                requireMoved(change);
            }
        }
    }

    /**
     * What a self-service password change came to, decided once on the state read under the
     * User's lock and already recorded as its {@code PASSWORD_CHANGE}: completed, refused by the
     * check — the User's standing, or a current password that is not the stored one — or refused
     * by the password policy. The caller reports it and decides nothing of it.
     */
    public sealed interface PasswordChangeVerdict {

        /**
         * What the change moved: a completed change's cleared run, basis and flag, or the failure
         * a wrong current password counted — nothing for any other refusal.
         */
        LoginStateChange change();

        /**
         * The change completed, at {@link LoginStateChange#completedAt}, with its
         * {@code PASSWORD_CHANGE} recorded and every Session's revocation queued for the commit.
         *
         * @param credential the new password it stored, which the caller remembers in the
         *     password history as of the completion, in the same transaction
         */
        record Changed(LoginStateChange change, PasswordAcceptance.Accepted credential)
                implements PasswordChangeVerdict {

            public Changed {
                requireMoved(change);
                Objects.requireNonNull(credential, "a completed change names what it stored");
            }
        }

        /**
         * Refused by the check, for {@code reason}: a standing ({@code ACCOUNT_LOCKED},
         * {@code ACCOUNT_DISABLED}) or a wrong current password ({@code BAD_CURRENT_PASSWORD}).
         */
        record Refused(LoginStateChange change, AuditPasswordChangeRefusal reason)
                implements PasswordChangeVerdict {

            public Refused {
                requireMoved(change);
                Objects.requireNonNull(reason, "a refusal has a reason");
            }
        }

        /** Refused because the new password broke {@code rule}; nothing is counted or written. */
        record RuleBroken(LoginStateChange change, PasswordPolicy.Rule rule)
                implements PasswordChangeVerdict {

            public RuleBroken {
                requireMoved(change);
                Objects.requireNonNull(rule, "a policy refusal names its rule");
            }

            /** The reason the refusal is recorded and logged as: the rule, in the trail's terms. */
            public AuditPasswordChangeRefusal reason() {
                return switch (rule) {
                    case TOO_SHORT -> AuditPasswordChangeRefusal.TOO_SHORT;
                    case TOO_LONG -> AuditPasswordChangeRefusal.TOO_LONG;
                    case CONTAINS_USER_NAME -> AuditPasswordChangeRefusal.CONTAINS_USER_NAME;
                    case REUSED -> AuditPasswordChangeRefusal.REUSED;
                };
            }
        }
    }

    /** Every verdict hands back what it moved, even when that is nothing. */
    private static void requireMoved(LoginStateChange change) {
        Objects.requireNonNull(change, "a verdict hands back what it moved");
    }

    /**
     * A User as read under its lock: its stable id, whether its reservation marker exempts it from
     * Lockout, and its login state as stored.
     */
    private record Locked(UUID id, boolean exemptFromLockout, ScimLoginState state) {
    }

    private final ScimLoginStateRepository loginStates;
    private final ScimUserRepository users;
    private final LoginIdentityService identities;
    private final ScimWriteLocks locks;
    private final SessionRevocationService sessions;
    private final LockoutPolicy policy;
    private final AuditTrail audit;
    private final Clock clock;

    /**
     * @param users      the directory's Users, read under the lock for the reservation marker
     *                   alone
     * @param identities reads, under the lock, the authority an accepted Login's session is
     *                   issued with
     * @param locks      takes the User's resource lock before each event decides, so every write
     *                   of the login state is serialized here rather than by its caller
     */
    public LoginStateService(
            ScimLoginStateRepository loginStates,
            ScimUserRepository users,
            LoginIdentityService identities,
            ScimWriteLocks locks,
            SessionRevocationService sessions,
            LockoutPolicy policy,
            AuditTrail audit,
            Clock clock) {
        this.loginStates = loginStates;
        this.users = users;
        this.identities = identities;
        this.locks = locks;
        this.sessions = sessions;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * A password Login of the User {@code userId} its authentication accepted, having verified
     * {@code credential}: decided, and a refusal counted, on the one state read under the User's
     * lock, which is taken once — the refusal cannot be counted on a state it was not decided
     * from, and there is no moment between the two in which a second writer could land. The
     * caller's transaction therefore holds both, and commits them together.
     *
     * <p>Its run is cleared and its dormancy basis moved unless the session is confined, and its
     * session's authority read ({@link #accepted}) — unless the standing under the lock refuses it
     * ({@code ACCOUNT_LOCKED}, {@code ACCOUNT_DISABLED}), or the stored credential is no longer
     * the one the authentication verified: a password replaced while the old one was being
     * compared — by a connector, an Admin, or the User's own change elsewhere — would otherwise
     * sign in, after the replacement had revoked the User's sessions, a Login made with a password
     * that no longer opens the account. That Login is refused as the wrong password it is at the
     * moment of the decision, {@code BAD_CREDENTIALS}. A refusal here is counted as the
     * authentication's refusal for the same reason is ({@link #passwordLoginRefused}): a replaced
     * credential's and a deactivation's lengthen the run, and may impose the Lockout, while a
     * Lockout's counts nothing.
     *
     * @return the verdict — accepted with its session's authority, or refused and what counting
     *     it moved — or empty when the User is gone under its lock, with nothing written, counted
     *     or audited
     */
    public Optional<LoginVerdict<PasswordRefusalReason>> passwordLogin(
            UUID userId, VerifiedPassword credential) {
        Objects.requireNonNull(credential, "an accepted password Login verified a credential");
        return underLock(userId).map(user -> {
            PasswordRefusalReason refusal = standingOf(user)
                    .map(StandingRefusal::passwordReason)
                    .orElseGet(() -> credential.storedIn(user.state())
                            ? null : PasswordRefusalReason.BAD_CREDENTIALS);
            if (refusal != null) {
                return new LoginVerdict.Refused<>(countedFor(user, refusal), refusal);
            }
            // The change-required flag marks the very credential the Login presented.
            return accepted(user, user.state().isPasswordChangeRequired());
        });
    }

    /**
     * A password Login of the User {@code userId} its authentication refused for
     * {@code reason}: counted for that reason under the User's lock. A Login refused
     * {@code ACCOUNT_LOCKED} counts nothing, whatever the state read under the lock now holds,
     * and nothing is written. The authentication's refusal was decided on its read, before this
     * runs, and an Admin Unlock can commit in between, leaving an unlocked User with a cleared
     * run. Counting the attempt then would make one refused for the Lockout the first failure of
     * the run the Unlock started, when an attempt made while a Lockout holds neither counts nor
     * deepens it (ADR 0007). So the verdict is the refusal's, not the re-read state's. Every
     * other reason is counted.
     *
     * @return the refusal and what counting it moved, or empty when the User is gone under its
     *     lock, with nothing written, counted or audited
     */
    public Optional<LoginVerdict.Refused<PasswordRefusalReason>> passwordLoginRefused(
            UUID userId, PasswordRefusalReason reason) {
        Objects.requireNonNull(reason, "a refusal has a reason");
        return underLock(userId)
                .map(user -> new LoginVerdict.Refused<>(countedFor(user, reason), reason));
    }

    /**
     * An Epic Login of the User {@code userId} (D12): its run cleared, its dormancy basis moved
     * and its session's authority read ({@link #accepted}) — an Epic Login is never confined,
     * because it presented no password of ours (D20), and for the same reason there is no
     * credential of ours it verified that could have been replaced since, so none is checked.
     *
     * <p>Unless the User's standing, read under its lock, refuses the Login: a Lockout, or a User
     * no longer active. The Login's lookup read both before the lock, and a Lockout or a
     * deactivation committed since would otherwise be cleared by the Login it should refuse —
     * lifting a lock no Unlock lifted (ADR 0007). Refused fail closed: nothing is written, and its
     * refusal counts toward no failure run. Locked first, as the authentication checks it.
     *
     * @return the verdict, or empty when the User is gone under its lock, with nothing written
     */
    public Optional<LoginVerdict<EpicLoginFailureReason>> epicLogin(UUID userId) {
        return underLock(userId).map(user -> standingOf(user)
                .<LoginVerdict<EpicLoginFailureReason>>map(standing -> new LoginVerdict.Refused<>(
                        LoginStateChange.none(user.state()), standing.epicReason()))
                .orElseGet(() -> accepted(user, false)));
    }

    /** The standing that refuses {@code user}'s Login, read under its lock, if any. */
    private Optional<StandingRefusal> standingOf(Locked user) {
        return StandingRefusal.of(user.state(), loginStates.isActive(user.id()));
    }

    /**
     * {@code user}'s Login accepted under its lock: its run cleared, its dormancy basis moved
     * unless the session is {@code confined}, and the authority its session is issued with read
     * here, under the same lock and in the same transaction as the standing that accepted it — not
     * from the authentication's read before the lock, which a Group membership or Group
     * Permissions change committed during the comparison would leave behind. Such a change's own
     * revocation runs after its commit and finds no session for this Login yet, so this read is
     * the only one that can keep the removed power out of the session
     * ({@code /docs/adr/0002-revoke-sessions-after-commit.md}).
     */
    private <R> LoginVerdict.Accepted<R> accepted(Locked user, boolean confined) {
        ScimLoginState before = user.state();
        ScimLoginState after = before.withFailureRunCleared();
        if (!confined) {
            after = after.withDormancyBasisReset(clock.instant());
        }
        loginStates.writeChange(user.id(), before, after);
        return new LoginVerdict.Accepted<>(LoginStateChange.between(before, after), confined,
                identities.sessionAuthorities(user.id(), confined));
    }

    /**
     * A self-service password change of the User {@code userId} whose current password did not
     * verify against the credential its caller read — or which had none to verify: decided, and
     * recorded as its refused {@code PASSWORD_CHANGE}, on the state read under the User's lock.
     *
     * <p>The User's standing comes first, as for a Login: a Lockout or a deactivation refuses the
     * change for itself ({@code ACCOUNT_LOCKED}, {@code ACCOUNT_DISABLED}) and counts nothing, so
     * an attempt against a Lockout neither counts nor deepens it. Otherwise the wrong current
     * password is counted exactly as a refused password Login is, so the change is not a second
     * guessing surface beside Login, and refused as {@code BAD_CURRENT_PASSWORD}, naming what
     * counting it moved — after a Lockout it imposed, whose own record lands first.
     *
     * @return the refusal and what counting it moved, or empty when the User is gone under its
     *     lock, with nothing written, counted or audited
     */
    public Optional<PasswordChangeVerdict.Refused> passwordChangeRefused(UUID userId) {
        return underLock(userId).map(user -> standingOf(user)
                .map(standing -> refuseForStanding(user, standing))
                .orElseGet(() -> refuseForWrongCurrentPassword(user)));
    }

    /**
     * A self-service change of the User {@code userId}'s password, its current password having
     * verified against {@code current} and the new one decided on as {@code newPassword} — both
     * by the caller, before this and in no transaction, so no Argon2id comparison or encoding runs
     * while the User's lock is held. Confirmed here on the state read under the lock, in this
     * order, and recorded as its {@code PASSWORD_CHANGE}:
     *
     * <ol>
     *   <li>the User's standing refuses it, counting nothing ({@code ACCOUNT_LOCKED},
     *       {@code ACCOUNT_DISABLED});
     *   <li>a credential no longer the one {@code current} names — replaced or removed while the
     *       password was being compared — makes the submitted one wrong by now: counted and
     *       refused as {@link #passwordChangeRefused} refuses one ({@code BAD_CURRENT_PASSWORD}).
     *       A salted hash differs on every write, so a password set again to the same value
     *       counts as replaced, as for a password Login;
     *   <li>a new password the policy refused is refused for its rule, counting nothing;
     *   <li>otherwise the change completes: the run is cleared, the credential stored and the
     *       flag cleared in one write, and the dormancy basis moved — for a User that owed the
     *       change it is the first use of the account, since its confined Logins did not move the
     *       basis. Then its {@code PASSWORD_CHANGE} is recorded, and the revocation of every
     *       Session of the User, the submitter's included, queued for after the commit
     *       ({@code /docs/adr/0002-revoke-sessions-after-commit.md}).
     * </ol>
     *
     * <p>The policy's decision stands on the state confirmed here: the retained history moves only
     * with the credential, which this has just confirmed unchanged, and the {@code userName} rule
     * read the name as of the caller's read — a rename landing since checks no password, so the
     * change ending as if it committed first is one serial order of the two.
     *
     * @return the verdict — completed, refused by the check, or refused by the policy — or empty
     *     when the User is gone, under its lock or by the completion, with nothing written,
     *     counted or audited
     */
    public Optional<PasswordChangeVerdict> passwordChange(
            UUID userId, VerifiedPassword current, PasswordAcceptance.Decision newPassword) {
        Objects.requireNonNull(current, "a password change verified its current password");
        Objects.requireNonNull(newPassword, "a password change decided on its new password");
        return underLock(userId).flatMap(user -> {
            Optional<StandingRefusal> standing = standingOf(user);
            if (standing.isPresent()) {
                return Optional.of(refuseForStanding(user, standing.get()));
            }
            if (!current.storedIn(user.state())) {
                return Optional.of(refuseForWrongCurrentPassword(user));
            }
            return switch (newPassword) {
                case PasswordAcceptance.Refused refused ->
                        Optional.of(refuseForRule(user, refused));
                case PasswordAcceptance.Accepted accepted -> completeAndRecord(user, accepted);
            };
        });
    }

    /** {@code user}'s change refused on {@code standing}, recorded as moving nothing. */
    private PasswordChangeVerdict.Refused refuseForStanding(
            Locked user, StandingRefusal standing) {
        return recordRefusal(user, LoginStateChange.none(user.state()),
                standing.passwordChangeReason());
    }

    /** {@code user}'s wrong current password, counted and recorded as what counting it moved. */
    private PasswordChangeVerdict.Refused refuseForWrongCurrentPassword(Locked user) {
        return recordRefusal(
                user, counted(user), AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD);
    }

    /**
     * Records {@code user}'s refused change — fail-open, as every refusal on this path is — naming
     * what {@code change} moved, and hands back the refusal.
     */
    private PasswordChangeVerdict.Refused recordRefusal(
            Locked user, LoginStateChange change, AuditPasswordChangeRefusal reason) {
        audit.append(new Audited.PasswordChangeRefused(user.id(), reason, change.moved()));
        return new PasswordChangeVerdict.Refused(change, reason);
    }

    /** {@code user}'s change refused for the rule its new password broke, recorded as that rule. */
    private PasswordChangeVerdict.RuleBroken refuseForRule(
            Locked user, PasswordAcceptance.Refused refused) {
        PasswordChangeVerdict.RuleBroken broken = new PasswordChangeVerdict.RuleBroken(
                LoginStateChange.none(user.state()), refused.rule());
        audit.append(new Audited.PasswordChangeRefused(user.id(), broken.reason()));
        return broken;
    }

    /**
     * {@code user}'s change completed as of now with {@code accepted}'s credential, then its
     * fail-closed {@code PASSWORD_CHANGE} recorded and every Session's revocation queued — in that
     * order, so a record the trail cannot write rolls the change back with it. Empty when the
     * User is gone by the completion, with nothing else written.
     */
    private Optional<PasswordChangeVerdict> completeAndRecord(
            Locked user, PasswordAcceptance.Accepted accepted) {
        Instant now = clock.instant();
        return completeLocked(user, accepted.passwordHash(), now, now).map(change -> {
            audit.append(new Audited.PasswordChanged(user.id(), change.moved()));
            sessions.revokeAllAfterCommit(
                    user.id(), SessionRevocationCause.PASSWORD_CHANGED, null);
            return new PasswordChangeVerdict.Changed(change, accepted);
        });
    }

    /**
     * {@code user}'s password Login refused for {@code reason}: nothing for a refusal for a
     * Lockout, as {@link #passwordLoginRefused} says why, and otherwise counted.
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
     * An Admin Unlock of the User {@code userId} by the administrator {@code actorId}: clears the
     * run and the Lockout, restarts the dormancy basis and, for a User with a password, requires a
     * change — the credential that reached the limit may be the one an attacker was guessing.
     * Then, after the state is written, it is recorded: its {@code LOCKOUT_LIFT}, naming the lifted
     * lock's cause and what the Unlock moved, and — when it required a change — its
     * {@code PASSWORD_CHANGE_REQUIRE}, both carrying the administrator. Each is fail-closed, a part
     * of the Unlock (ADR 0004), so one the trail cannot record rolls the Unlock back. No Session is
     * revoked: a locked User holds none, since imposing the lock ended them.
     *
     * <p>A User serving no lockout is left exactly as it is stored, its run included, and nothing
     * is written or recorded: an Unlock has no effect on an unlocked User. Whether a lock stood is
     * read from the state under the User's lock, and the administration path refuses that Unlock
     * on the verdict (ADR 0015).
     *
     * @return the verdict — lifted, with what it moved and the state it stored, the lifted lock's
     *     cause being the state it read; or not locked, moving nothing — or empty when the User is
     *     gone under its lock
     */
    public Optional<UnlockVerdict> unlocked(UUID userId, UUID actorId) {
        Objects.requireNonNull(actorId, "an Unlock names its administrator");
        return underLock(userId).map(user -> {
            ScimLoginState before = user.state();
            if (!before.isLocked()) {
                return new UnlockVerdict.NotLocked(LoginStateChange.none(before));
            }
            Instant now = clock.instant();
            ScimLoginState after = before.withFailureRunCleared().withDormancyBasisReset(now);
            if (before.hasPassword()) {
                after = after.withPasswordChangeRequired(now);
            }
            loginStates.writeChange(user.id(), before, after);
            LoginStateChange lifted = LoginStateChange.between(before, after);
            audit.append(new Audited.LockoutLiftedByUnlock(
                    actorId, user.id(), auditCause(before.lockCause()), lifted.moved()));
            if (lifted.hasMoved(AuditLoginStateAttribute.PASSWORD_CHANGE_REQUIRED_SINCE)) {
                audit.append(new Audited.PasswordChangeRequired(actorId, user.id(), lifted.moved()));
            }
            return new UnlockVerdict.Lifted(lifted);
        });
    }

    /** The lifted lock's cause in the audit trail's own vocabulary. */
    private static AuditLockCause auditCause(LockCause cause) {
        return switch (cause) {
            case FAILURES -> AuditLockCause.FAILURES;
            case DORMANCY -> AuditLockCause.DORMANCY;
        };
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
     * @return the verdict — imposed, with the lock and its cause it moved; or already locked,
     *     moving nothing, when the store refused to overwrite a standing lock — or empty when the
     *     User is gone under its lock
     */
    public Optional<DormancyLockVerdict> dormancyLocked(UUID userId, Instant now) {
        return underLock(userId).map(user -> {
            ScimLoginState before = user.state();
            ScimLoginState locked = before.withDormancyLock(now);
            if (!loginStates.imposeLock(
                    user.id(), new Lockout(locked.lockedAt(), locked.lockCause()))) {
                return new DormancyLockVerdict.AlreadyLocked(LoginStateChange.none(before));
            }
            return new DormancyLockVerdict.Imposed(
                    written(user.id(), LoginStateChange.between(before, locked)));
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
        return completeUnderLock(fixture.id(), passwordHash, now, now.minus(dormantFor))
                .orElseGet(() -> LoginStateChange.none(fixture.login()));
    }

    /**
     * Completes a change of the User {@code userId}'s password to {@code passwordHash} as of
     * {@code completedAt}, then clears its run and moves its dormancy basis to {@code basis}: the
     * shared transition of a self-service change and the dormant fixture's reset. Takes the User's
     * lock ({@link #completeLocked}).
     */
    private Optional<LoginStateChange> completeUnderLock(
            UUID userId, String passwordHash, Instant completedAt, Instant basis) {
        return underLock(userId)
                .flatMap(user -> completeLocked(user, passwordHash, completedAt, basis));
    }

    /**
     * Completes {@code user}'s change of password, read under its lock. Whatever it read, the state
     * the run and the basis are written from is the one the completion left, so the change cannot
     * write back the flag the completion cleared, and a User gone by the completion has nothing
     * else written either.
     *
     * @return what the change moved from the state read under the lock, and {@code completedAt};
     *     empty when the User was gone by the completion, and nothing was written
     */
    private Optional<LoginStateChange> completeLocked(
            Locked user, String passwordHash, Instant completedAt, Instant basis) {
        return loginStates.completePasswordChange(user.id(), passwordHash, completedAt)
                .map(ScimUser::login)
                .map(completed -> {
                    ScimLoginState after =
                            completed.withFailureRunCleared().withDormancyBasisReset(basis);
                    loginStates.writeChange(user.id(), completed, after);
                    return new LoginStateChange(user.state(), after, completedAt);
                });
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
