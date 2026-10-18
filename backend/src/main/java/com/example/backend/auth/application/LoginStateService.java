package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.LockoutPolicy;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The login-state module: the one owner of a User's Failure run and lock, its dormancy basis and
 * its change-required flag. A caller reports what happened — a failure counted, a credential
 * verified, a password changed, an Unlock, an Admin's forced change, a dormancy lock, a directory
 * write — and this decides the resulting state and writes what it changed, or hands the state
 * back to be stored with the write it belongs to. No caller decides those fields
 * itself.
 *
 * <p>The rules, event by event:
 *
 * <ul>
 *   <li><b>Failure counted</b> — a refused password Login or a self-service change's wrong current
 *       password: the run lengthens, and reaching the {@link LockoutPolicy}'s limit imposes a
 *       Lockout for failures as of now. A User already locked is left as it is, whatever its lock
 *       cause. The Bootstrap Admin's failures are counted and never lock, read off its own
 *       reservation marker ({@code /docs/adr/0007-lockout-lifted-only-by-admin-unlock.md}).
 *   <li><b>Credential verified</b> — an accepted Login: the run is cleared, and the dormancy basis
 *       moves unless the Login builds a Confined session (ADR 0008 and its addendum).
 *   <li><b>Password changed</b> — the run is cleared, the change is completed (the new credential
 *       stored and the flag cleared in one write), and the dormancy basis moves.
 *   <li><b>Unlocked</b> — an Admin Unlock: the run and the Lockout are cleared, and when a lock
 *       stood the dormancy basis restarts (ADR 0007) and, for a User with a password, a change is
 *       required. Idempotent on a User serving no lockout: only its run is cleared.
 *   <li><b>Password change forced</b> — an Admin's forced password change: a change is required
 *       as of now, the run and any lock untouched. Idempotent on a User already owing one: it
 *       keeps the instant the change was first required, and nothing is written. Who may force a
 *       change, and on whom, is the administration path's to refuse before it reports the event.
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
 * <p>Whether a Login builds a Confined session is decided here ({@link #confines}), and both of its
 * consequences read that one answer: the dormancy basis this module moves, and the authorities
 * {@link LoginIdentityService} issues. A password Login by a User whose flag is set is confined; an
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
 * store that fails is recorded and alerted without changing the path's bare refusal (ADR 0004's
 * 2026-10-10 addendum). The {@code LOCKOUT_SET} append is fail-open, as everything on the failure
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

    /** The kind of Login that verified a credential, which decides whether it is confined. */
    public enum LoginKind {
        /** A password Login: the change-required flag marks the very credential it presented. */
        PASSWORD,
        /** An Epic Login: it presented no password of ours, so the flag does not confine it. */
        EPIC
    }

    /**
     * What an Unlock decided, and so what it changed.
     *
     * @param state           the User's login state as the Unlock left it
     * @param changeRequired  whether the Unlock required a password change
     * @param lifted          the cause of the lock the Unlock lifted, which also restarted the
     *                        dormancy basis — or {@code null} when no lock stood
     * @param attemptsCleared whether the failure run held attempts the Unlock cleared
     */
    public record Unlock(
            ScimLoginState state, boolean changeRequired, LockCause lifted,
            boolean attemptsCleared) {
    }

    /**
     * What an Admin's forced password change decided.
     *
     * @param state   the User's login state as the forced change left it
     * @param imposed whether the forced change required a change the User did not already owe
     */
    public record ForcedChange(ScimLoginState state, boolean imposed) {
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
     * Whether a Login of this kind, by a User in this state, builds a Confined session: one that
     * may only change the password or log out.
     */
    public static boolean confines(LoginKind kind, ScimLoginState state) {
        return kind == LoginKind.PASSWORD && state.isPasswordChangeRequired();
    }

    /**
     * A refused password Login or password-change attempt against {@code user}: counts it, and on a
     * newly imposed Lockout audits it and revokes every Session of the User after the commit.
     * Counted on the run stored under the User's lock, so a parallel failure is never lost and
     * only the one that reaches the limit imposes the Lockout.
     */
    public void failureCounted(ScimUser user) {
        ScimLoginState before = stateUnderLock(user);
        ScimLoginState after = user.isExemptFromLockout()
                ? before.withFailureCounted()
                : before.withFailureRecorded(policy, clock.instant());
        loginStates.writeChange(user.id(), before, after);
        if (after.isLocked() && !before.isLocked()) {
            audit.append(new Audited.LockoutSet(user.id()));
            sessions.revokeAllAfterCommit(
                    user.id(), SessionRevocationCause.FAILURE_RUN_LOCKOUT, null);
        }
    }

    /**
     * An accepted Login by {@code user}: clears its run, and moves its dormancy basis unless the
     * Login builds a Confined session — a confined session can do nothing but change the password
     * or log out, so it is not use of the account, and counting it would let an imposed credential
     * nobody replaces stay live for as long as somebody keeps logging in with it (ADR 0008).
     */
    public void credentialVerified(ScimUser user, LoginKind kind) {
        ScimLoginState before = stateUnderLock(user);
        ScimLoginState after = before.withFailureRunCleared();
        if (!confines(kind, before)) {
            after = after.withDormancyBasisReset(clock.instant());
        }
        loginStates.writeChange(user.id(), before, after);
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
     * @return the instant the change was completed at, which the credential, the dormancy basis
     *     and the caller's password history all share; empty when the User was gone by the write,
     *     in which case the basis is not moved
     */
    public Optional<Instant> passwordChanged(ScimUser user, String passwordHash) {
        locks.lock(List.of(user.id()));
        Instant now = clock.instant();
        return changeCompleted(user.id(), passwordHash, now, now)
                ? Optional.of(now)
                : Optional.empty();
    }

    /**
     * An Admin Unlock of {@code user}: clears the run and any Lockout, and when a lock stood
     * restarts the dormancy basis and, for a User with a password, requires a change — the
     * credential that reached the limit may be the one an attacker was guessing. A User serving no
     * lockout has only its run cleared.
     *
     * @return the state the Unlock stored, and whether it required a change
     */
    public Unlock unlocked(ScimUser user) {
        ScimLoginState before = stateUnderLock(user);
        ScimLoginState after = before.withFailureRunCleared();
        boolean changeRequired = false;
        if (before.isLocked()) {
            Instant now = clock.instant();
            after = after.withDormancyBasisReset(now);
            if (before.hasPassword()) {
                after = after.withPasswordChangeRequired(now);
                changeRequired = true;
            }
        }
        loginStates.writeChange(user.id(), before, after);
        return new Unlock(after, changeRequired, before.lockCause(),
                before.failedLoginAttempts() > 0);
    }

    /**
     * An Admin forced a password change on {@code user}: requires a change as of now, leaving the
     * run and any lock as they stand — the credential's standing is withdrawn, not its use
     * counted. A User already owing a change keeps the instant it was first required, so a second
     * forced change re-imposes nothing; the administration path's audit and Session revocation
     * follow only a change this imposed.
     *
     * @return the state the forced change stored, and whether it imposed a change
     */
    public ForcedChange passwordChangeForced(ScimUser user) {
        ScimLoginState before = stateUnderLock(user);
        if (before.isPasswordChangeRequired()) {
            return new ForcedChange(before, false);
        }
        ScimLoginState after = before.withPasswordChangeRequired(clock.instant());
        loginStates.writeChange(user.id(), before, after);
        return new ForcedChange(after, true);
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
     * @return whether this imposed the lock
     */
    public boolean dormancyLocked(ScimUser user, Instant now) {
        ScimLoginState locked = stateUnderLock(user).withDormancyLock(now);
        return loginStates.imposeLock(
                user.id(), new Lockout(locked.lockedAt(), locked.lockCause()));
    }

    /**
     * A User was created — by a connector, or as the seeded Bootstrap Admin: a credential somebody
     * else chose (a connector's, or the deployment's configured one) is known outside the User,
     * so a User created with one owes a change as of its creation. A User created without one owes
     * nothing; the flag arrives with its first password.
     */
    @Override
    public ScimLoginState created(String passwordHash, Instant now) {
        ScimLoginState created = ScimLoginState.of(passwordHash);
        return created.hasPassword() ? created.withPasswordChangeRequired(now) : created;
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
    public ScimLoginState afterReplacement(
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
        return after;
    }

    /**
     * The development seed reset its dormant fixture: the fixture password back with no change
     * owed, the run and any lock cleared, and the dormancy basis {@code dormantFor} before
     * {@code now}, so the development profile's startup run of the job locks it again.
     */
    @Override
    public void dormantFixtureReset(
            ScimUser fixture, String passwordHash, Instant now, Duration dormantFor) {
        locks.lock(List.of(fixture.id()));
        changeCompleted(fixture.id(), passwordHash, now, now.minus(dormantFor));
    }

    /**
     * Completes a change of User {@code id}'s password to {@code passwordHash} as of
     * {@code completedAt}, then clears its run and moves its dormancy basis to {@code basis}: the
     * shared tail of a self-service change and the dormant fixture's reset.
     *
     * <p>The caller holds the User's lock and has read no state: the state the run and the basis
     * are written from is the one the completion left, so the change cannot write back the flag
     * the completion cleared, and a User gone by the completion has nothing else written either.
     *
     * @return whether the User was still there to complete the change for
     */
    private boolean changeCompleted(
            UUID id, String passwordHash, Instant completedAt, Instant basis) {
        Optional<ScimLoginState> completed = loginStates
                .completePasswordChange(id, passwordHash, completedAt)
                .map(ScimUser::login);
        completed.ifPresent(before -> loginStates.writeChange(
                id, before, before.withFailureRunCleared().withDormancyBasisReset(basis)));
        return completed.isPresent();
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
