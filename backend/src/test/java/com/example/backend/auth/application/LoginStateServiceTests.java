package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditLoginStateAttribute;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.application.LoginStateService.PasswordAnswer;
import com.example.backend.auth.application.LoginStateService.PasswordLoginDecision;
import com.example.backend.auth.application.LoginStateService.StandingRefusal;
import com.example.backend.auth.application.LoginStateService.Verification;
import com.example.backend.auth.application.LoginStateService.VerifiedCredential;
import com.example.backend.auth.application.LoginStateService.VerifiedPassword;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserLoginState.Credential;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The login-state module: what each event — a password Login, an Epic Login, a self-service
 * change's wrong current password, a completed password change, an Unlock of a locked User, a
 * dormancy lock — leaves stored as the User's login state, read under the User's lock from the
 * stable id the event names, and that a User gone there is answered as gone, with nothing written
 * or recorded; what each directory event — a create, a SCIM replacement with its Reactivation, the
 * dormant fixture's reset — leaves; and what a newly imposed Lockout implies, for either cause:
 * one audit event and one revocation of every Session, after the commit.
 *
 * <p>The paths that report the events are tested for what is theirs alone (their refusal event,
 * their response, the order their refusal lands in) in {@link PasswordLoginRecordingTests},
 * {@link EpicLoginDecisionTests}, {@link PasswordChangeServiceTests} and
 * {@link IdentityAdministrationServiceTests}; nothing about the rules themselves is restated there.
 */
class LoginStateServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    /** The dormancy basis, the lock and the flag every arranged state carries, all before NOW. */
    private static final Instant EARLIER = Instant.parse("2026-06-01T00:00:00Z");

    private static final int LIMIT = 3;

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();
    private final InMemoryScimWriteLocks locks = new InMemoryScimWriteLocks();

    private LoginStateService loginState = moduleDeferringTo(transaction);

    /** What a password Login's authentication verified: the credential every arranged state holds. */
    private static final VerifiedPassword PASSWORD = VerifiedCredential.passwordHash("hash");

    /** The route a Login came by, naming each parameterized case. */
    private enum Route { PASSWORD, EPIC }

    /** What a Login by {@code route} verified, against the credential every arranged state holds. */
    private static VerifiedCredential verifiedBy(Route route) {
        return route == Route.PASSWORD ? PASSWORD : VerifiedCredential.epic();
    }

    /** A Login by {@code route} of the User {@code userId}, its password verified, as decided. */
    private Decided logIn(Route route, UUID userId) {
        if (route == Route.PASSWORD) {
            PasswordLoginDecision decided = loginState.passwordLogin(userId, PASSWORD).orElseThrow();
            return new Decided(decided.refused(), decided.refusal(), decided.confined());
        }
        Verification decided = loginState.epicLogin(userId).orElseThrow();
        return new Decided(decided.refused(), decided.refusal(), decided.confined());
    }

    /**
     * A Login's decision as either route reports it: whether it was refused, the refusal in the
     * route's own terms — a password Login's {@link PasswordRefusalReason}, an Epic Login's
     * {@link StandingRefusal} — and whether the accepted Login is confined.
     */
    private record Decided(boolean refused, Object refusal, boolean confined) {
    }

    // ---- the rules, one row per case ---------------------------------------------------------

    /** An event as its caller reports it, naming the User by stable id. */
    private enum Event {
        PASSWORD_CHANGE_FAILED((module, user) -> module.passwordChangeFailed(user)),
        LOGIN_REFUSED_AS_A_WRONG_PASSWORD((module, user) -> module.passwordLogin(user,
                        PasswordAnswer.refused(PasswordRefusalReason.BAD_CREDENTIALS))
                .map(PasswordLoginDecision::change)),
        LOGIN_REFUSED_FOR_A_LOCKOUT((module, user) -> module.passwordLogin(user,
                        PasswordAnswer.refused(PasswordRefusalReason.ACCOUNT_LOCKED))
                .map(PasswordLoginDecision::change)),
        PASSWORD_LOGIN((module, user) ->
                module.passwordLogin(user, PASSWORD).map(PasswordLoginDecision::change)),
        PASSWORD_LOGIN_WITH_A_REPLACED_CREDENTIAL((module, user) -> module.passwordLogin(
                user, VerifiedCredential.passwordHash("old-hash")).map(PasswordLoginDecision::change)),
        EPIC_LOGIN((module, user) -> module.epicLogin(user).map(Verification::change)),
        PASSWORD_CHANGED((module, user) -> module.passwordChanged(user, "new-hash")),
        UNLOCKED((module, user) -> module.unlocked(user)),
        DORMANCY_LOCKED((module, user) -> module.dormancyLocked(user, NOW));

        private final BiFunction<LoginStateService, UUID, Optional<LoginStateChange>> report;

        Event(BiFunction<LoginStateService, UUID, Optional<LoginStateChange>> report) {
            this.report = report;
        }

        /** What the event changed for a User there under its lock. */
        LoginStateChange reportedOn(LoginStateService module, UUID user) {
            return report.apply(module, user).orElseThrow();
        }
    }

    private static ScimLoginState run(int failures) {
        return new ScimLoginState("hash", failures, null, null, EARLIER, null);
    }

    private static ScimLoginState flaggedWithRun(int failures) {
        return new ScimLoginState("hash", failures, null, null, EARLIER, EARLIER);
    }

    /** A User with no password, carrying a run counted while it still had one. */
    private static ScimLoginState passwordlessWithRun(int failures) {
        return new ScimLoginState(null, failures, null, null, EARLIER, null);
    }

    private static ScimLoginState locked(LockCause cause) {
        return new ScimLoginState("hash", LIMIT, EARLIER, cause, EARLIER, null);
    }

    static Stream<Arguments> rules() {
        return Stream.of(
                row("a failure below the limit lengthens the run and locks nothing",
                        Event.PASSWORD_CHANGE_FAILED, false, run(1),
                        new ScimLoginState("hash", 2, null, null, EARLIER, null)),
                row("the failure reaching the limit locks for failures as of now",
                        Event.PASSWORD_CHANGE_FAILED, false, run(LIMIT - 1),
                        new ScimLoginState("hash", LIMIT, NOW, LockCause.FAILURES, EARLIER, null)),
                row("a failure on a User locked for failures neither deepens nor restarts it",
                        Event.PASSWORD_CHANGE_FAILED, false, locked(LockCause.FAILURES),
                        locked(LockCause.FAILURES)),
                row("a failure on a User locked for dormancy keeps the dormancy cause",
                        Event.PASSWORD_CHANGE_FAILED, false, locked(LockCause.DORMANCY),
                        locked(LockCause.DORMANCY)),
                row("the Bootstrap Admin's failure at the limit is counted and never locks",
                        Event.PASSWORD_CHANGE_FAILED, true, run(LIMIT - 1),
                        new ScimLoginState("hash", LIMIT, null, null, EARLIER, null)),
                row("a failure against a User with no password leaves its run as it stands",
                        Event.PASSWORD_CHANGE_FAILED, false, passwordlessWithRun(LIMIT - 1),
                        passwordlessWithRun(LIMIT - 1)),
                row("a Login refused as a wrong password lengthens the run",
                        Event.LOGIN_REFUSED_AS_A_WRONG_PASSWORD, false, run(1),
                        new ScimLoginState("hash", 2, null, null, EARLIER, null)),
                row("a Login refused for a Lockout an Unlock has lifted since counts nothing",
                        Event.LOGIN_REFUSED_FOR_A_LOCKOUT, false, run(LIMIT - 1),
                        run(LIMIT - 1)),
                row("a password Login clears the run and moves the dormancy basis",
                        Event.PASSWORD_LOGIN, false, run(2),
                        new ScimLoginState("hash", 0, null, null, NOW, null)),
                row("a confined Login does not move the dormancy basis",
                        Event.PASSWORD_LOGIN, false, flaggedWithRun(2),
                        new ScimLoginState("hash", 0, null, null, EARLIER, EARLIER)),
                row("an Epic Login is never confined: it moves the basis and leaves the flag set",
                        Event.EPIC_LOGIN, false, flaggedWithRun(2),
                        new ScimLoginState("hash", 0, null, null, NOW, EARLIER)),
                row("a password Login refused for a replaced credential lengthens the run",
                        Event.PASSWORD_LOGIN_WITH_A_REPLACED_CREDENTIAL, false, run(1),
                        new ScimLoginState("hash", 2, null, null, EARLIER, null)),
                row("a password Login refused for a replaced credential at the limit locks",
                        Event.PASSWORD_LOGIN_WITH_A_REPLACED_CREDENTIAL, false, run(LIMIT - 1),
                        new ScimLoginState("hash", LIMIT, NOW, LockCause.FAILURES, EARLIER, null)),
                row("a password Login refused for a Lockout found under the lock counts nothing",
                        Event.PASSWORD_LOGIN, false, locked(LockCause.FAILURES),
                        locked(LockCause.FAILURES)),
                row("so does the dormancy-locked Bootstrap Admin's, whose failures else count",
                        Event.PASSWORD_LOGIN, true, locked(LockCause.DORMANCY),
                        locked(LockCause.DORMANCY)),
                row("a password change clears the run, completes the change and moves the basis",
                        Event.PASSWORD_CHANGED, false, flaggedWithRun(2),
                        new ScimLoginState("new-hash", 0, null, null, NOW, null)),
                row("an Unlock lifts a failure lock, resets the basis and requires a change",
                        Event.UNLOCKED, false, locked(LockCause.FAILURES),
                        new ScimLoginState("hash", 0, null, null, NOW, NOW)),
                row("an Unlock lifts a dormancy lock, resets the basis and requires a change",
                        Event.UNLOCKED, false, locked(LockCause.DORMANCY),
                        new ScimLoginState("hash", 0, null, null, NOW, NOW)),
                row("an Unlock lifts a dormancy lock no failure run preceded",
                        Event.UNLOCKED, false,
                        new ScimLoginState("hash", 0, EARLIER, LockCause.DORMANCY, EARLIER, null),
                        new ScimLoginState("hash", 0, null, null, NOW, NOW)),
                row("an Unlock of a credentialless User requires no change",
                        Event.UNLOCKED, false,
                        new ScimLoginState(null, LIMIT, EARLIER, LockCause.FAILURES, EARLIER, null),
                        new ScimLoginState(null, 0, null, null, NOW, null)),
                row("an Unlock of an unlocked User leaves its run and all else as they are",
                        Event.UNLOCKED, false, run(2), run(2)),
                row("the dormancy job locks for dormancy as of now and leaves the run as it is",
                        Event.DORMANCY_LOCKED, false, flaggedWithRun(2),
                        new ScimLoginState("hash", 2, NOW, LockCause.DORMANCY, EARLIER, EARLIER)),
                row("the dormancy job keeps a failure lock and its cause",
                        Event.DORMANCY_LOCKED, false, locked(LockCause.FAILURES),
                        locked(LockCause.FAILURES)));
    }

    private static Arguments row(String rule, Event event, boolean bootstrapAdmin,
            ScimLoginState given, ScimLoginState expected) {
        return Arguments.of(rule, event, bootstrapAdmin, given, expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void eachEventLeavesTheLoginStateItsRuleDecides(String rule, Event event,
            boolean bootstrapAdmin, ScimLoginState given, ScimLoginState expected) {
        ScimUser user = ScimIdentities.userWithLoginState("ada", given);
        ScimUser stored = bootstrapAdmin
                ? users.createReserved(user, ReservedResourceName.BOOTSTRAP_ADMIN)
                : users.given(user);

        event.reportedOn(loginState, stored.id());

        assertThat(users.require("ada").login()).isEqualTo(expected);
    }

    // ---- what each event changed, which its audit rows name ----------------------------------

    private static final AuditLoginStateAttribute RUN = AuditLoginStateAttribute.FAILED_LOGIN_ATTEMPTS;
    private static final AuditLoginStateAttribute LOCKED_AT = AuditLoginStateAttribute.LOCKED_AT;
    private static final AuditLoginStateAttribute LOCK_CAUSE = AuditLoginStateAttribute.LOCK_CAUSE;
    private static final AuditLoginStateAttribute BASIS =
            AuditLoginStateAttribute.LAST_AUTHENTICATED_AT;
    private static final AuditLoginStateAttribute FLAG =
            AuditLoginStateAttribute.PASSWORD_CHANGE_REQUIRED_SINCE;

    static Stream<Arguments> movedPaths() {
        return Stream.of(
                moved("a failure below the limit moves the run",
                        Event.PASSWORD_CHANGE_FAILED, false, run(1), RUN),
                moved("the failure reaching the limit moves the run and the lock",
                        Event.PASSWORD_CHANGE_FAILED, false, run(LIMIT - 1), RUN, LOCKED_AT, LOCK_CAUSE),
                moved("a failure on a User locked for failures moves nothing",
                        Event.PASSWORD_CHANGE_FAILED, false, locked(LockCause.FAILURES)),
                moved("a failure on a User locked for dormancy moves nothing",
                        Event.PASSWORD_CHANGE_FAILED, false, locked(LockCause.DORMANCY)),
                moved("the Bootstrap Admin's failure at the limit moves the run alone",
                        Event.PASSWORD_CHANGE_FAILED, true, run(LIMIT - 1), RUN),
                moved("a failure against a User with no password moves nothing",
                        Event.PASSWORD_CHANGE_FAILED, false, passwordlessWithRun(LIMIT - 1)),
                moved("a Login refused as a wrong password moves the run",
                        Event.LOGIN_REFUSED_AS_A_WRONG_PASSWORD, false, run(1), RUN),
                moved("a Login refused for a Lockout an Unlock has lifted since moves nothing",
                        Event.LOGIN_REFUSED_FOR_A_LOCKOUT, false, run(LIMIT - 1)),
                moved("a password Login moves the run it clears and the basis",
                        Event.PASSWORD_LOGIN, false, run(2), RUN, BASIS),
                moved("a password Login with no run moves the basis alone",
                        Event.PASSWORD_LOGIN, false, run(0), BASIS),
                moved("a confined Login moves the run it clears and not the basis",
                        Event.PASSWORD_LOGIN, false, flaggedWithRun(2), RUN),
                moved("an Epic Login by a flagged User moves the run and the basis, not the flag",
                        Event.EPIC_LOGIN, false, flaggedWithRun(2), RUN, BASIS),
                moved("a password Login refused for a replaced credential moves the run",
                        Event.PASSWORD_LOGIN_WITH_A_REPLACED_CREDENTIAL, false, run(1), RUN),
                moved("a password Login refused for a replaced credential at the limit moves the"
                                + " run and the lock",
                        Event.PASSWORD_LOGIN_WITH_A_REPLACED_CREDENTIAL, false, run(LIMIT - 1),
                        RUN, LOCKED_AT, LOCK_CAUSE),
                moved("a password Login refused for a Lockout found under the lock moves nothing",
                        Event.PASSWORD_LOGIN, false, locked(LockCause.FAILURES)),
                moved("a password change moves the run, the basis and the flag it clears",
                        Event.PASSWORD_CHANGED, false, flaggedWithRun(2), RUN, BASIS, FLAG),
                moved("a password change by a User owing none moves the basis alone",
                        Event.PASSWORD_CHANGED, false, run(0), BASIS),
                moved("an Unlock of a failure lock moves the run, the lock, the basis and the flag",
                        Event.UNLOCKED, false, locked(LockCause.FAILURES),
                        RUN, LOCKED_AT, LOCK_CAUSE, BASIS, FLAG),
                moved("an Unlock of a dormancy lock no run preceded moves no run",
                        Event.UNLOCKED, false,
                        new ScimLoginState("hash", 0, EARLIER, LockCause.DORMANCY, EARLIER, null),
                        LOCKED_AT, LOCK_CAUSE, BASIS, FLAG),
                moved("an Unlock of a credentialless User moves no flag",
                        Event.UNLOCKED, false,
                        new ScimLoginState(null, LIMIT, EARLIER, LockCause.FAILURES, EARLIER, null),
                        RUN, LOCKED_AT, LOCK_CAUSE, BASIS),
                moved("an Unlock of an unlocked User moves nothing, its run included",
                        Event.UNLOCKED, false, run(2)),
                moved("an Unlock of a User with nothing to clear moves nothing",
                        Event.UNLOCKED, false, run(0)),
                moved("the dormancy lock moves the lock alone",
                        Event.DORMANCY_LOCKED, false, flaggedWithRun(2), LOCKED_AT, LOCK_CAUSE),
                moved("the dormancy lock of a User locked for failures moves nothing",
                        Event.DORMANCY_LOCKED, false, locked(LockCause.FAILURES)));
    }

    private static Arguments moved(String rule, Event event, boolean bootstrapAdmin,
            ScimLoginState given, AuditLoginStateAttribute... paths) {
        return Arguments.of(rule, event, bootstrapAdmin, given, List.of(paths));
    }

    /**
     * Every event hands back exactly the login-state paths it moved — the one source its audit
     * rows read their changed paths from.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("movedPaths")
    void eachEventHandsBackExactlyThePathsItMoved(String rule, Event event,
            boolean bootstrapAdmin, ScimLoginState given, List<AuditLoginStateAttribute> paths) {
        ScimUser user = ScimIdentities.userWithLoginState("ada", given);
        ScimUser stored = bootstrapAdmin
                ? users.createReserved(user, ReservedResourceName.BOOTSTRAP_ADMIN)
                : users.given(user);

        LoginStateChange change = event.reportedOn(loginState, stored.id());

        assertThat(change.moved()).containsExactlyInAnyOrderElementsOf(paths);
    }

    /** Each event hands back the state it left stored, which the administration path answers with. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Event.class)
    void eachEventHandsBackTheStateItLeftStored(Event event) {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", flaggedWithRun(2)));

        LoginStateChange change = event.reportedOn(loginState, stored.id());

        assertThat(change.after()).isEqualTo(users.require("ada").login());
    }

    // ---- serialization: the lock, and the state read under it -------------------------------

    /**
     * Every event takes the User's lock itself, so no caller decides whether its write is
     * serialized with the others (spec C6).
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Event.class)
    void everyEventTakesTheUsersLock(Event event) {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(1)));

        event.reportedOn(loginState, stored.id());

        assertThat(locks.requests()).containsExactly(Set.of(stored.id()));
    }

    @Test
    void theDormantFixtureResetTakesTheFixturesLock() {
        ScimUser fixture = users.given(ScimIdentities.userWithLoginState("ada", run(1)));

        loginState.dormantFixtureReset(fixture, "fixture-hash", NOW, Duration.ofDays(91));

        assertThat(locks.requests()).containsExactly(Set.of(fixture.id()));
    }

    static Stream<Arguments> writesBeforeTheLock() {
        return Stream.of(
                row("a failure counts on the run stored by then, so a parallel one is not lost",
                        Event.PASSWORD_CHANGE_FAILED, false, run(LIMIT - 1),
                        new ScimLoginState("hash", LIMIT, NOW, LockCause.FAILURES, EARLIER, null)),
                row("a failure on a User locked in the meantime imposes nothing",
                        Event.PASSWORD_CHANGE_FAILED, false, locked(LockCause.FAILURES),
                        locked(LockCause.FAILURES)),
                row("the Bootstrap Admin's failure counts on the stored run",
                        Event.PASSWORD_CHANGE_FAILED, true, run(LIMIT - 1),
                        new ScimLoginState("hash", LIMIT, null, null, EARLIER, null)),
                row("a failure against a User whose password was removed in the meantime counts"
                                + " nothing",
                        Event.PASSWORD_CHANGE_FAILED, false, passwordlessWithRun(LIMIT - 1),
                        passwordlessWithRun(LIMIT - 1)),
                row("a Login clears a run counted in the meantime",
                        Event.PASSWORD_LOGIN, false, run(2),
                        new ScimLoginState("hash", 0, null, null, NOW, null)),
                row("a Login is confined by a flag set in the meantime",
                        Event.PASSWORD_LOGIN, false, flaggedWithRun(0),
                        flaggedWithRun(0)),
                row("a password change clears a run counted in the meantime",
                        Event.PASSWORD_CHANGED, false, run(2),
                        new ScimLoginState("new-hash", 0, null, null, NOW, null)),
                row("an Unlock lifts a lock imposed in the meantime",
                        Event.UNLOCKED, false, locked(LockCause.FAILURES),
                        new ScimLoginState("hash", 0, null, null, NOW, NOW)));
    }

    /**
     * Each event decides from the state stored when it holds the lock, not from anything read
     * before it: every User here is stored with a clean record, and a second writer commits the
     * state that decides just before the event's lock is granted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("writesBeforeTheLock")
    void eachEventDecidesFromTheStateStoredWhenItTakesTheLock(String rule, Event event,
            boolean bootstrapAdmin, ScimLoginState storedByThen, ScimLoginState expected) {
        ScimUser user = ScimIdentities.userWithLoginState("ada", run(0));
        ScimUser stored = bootstrapAdmin
                ? users.createReserved(user, ReservedResourceName.BOOTSTRAP_ADMIN)
                : users.given(user);
        storeBeforeTheLock(stored, storedByThen);

        event.reportedOn(loginState, stored.id());

        assertThat(users.require("ada").login()).isEqualTo(expected);
    }

    /** The Unlock's answer is the decision made on the state stored when it took the lock. */
    @Test
    void anUnlockReportsTheLockStoredWhenItTookTheLock() {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(0)));
        storeBeforeTheLock(stored, locked(LockCause.DORMANCY));

        LoginStateChange decided = loginState.unlocked(stored.id()).orElseThrow();

        assertThat(List.of(Optional.ofNullable(decided.before().lockCause()), decided.moved()))
                .containsExactly(Optional.of(LockCause.DORMANCY),
                        Set.of(RUN, LOCKED_AT, LOCK_CAUSE, BASIS, FLAG));
    }

    /** The fixture reset clears a run and a lock stored since its copy was read. */
    @Test
    void theDormantFixtureResetClearsTheStoredRunAndLock() {
        ScimUser fixture = users.given(ScimIdentities.userWithLoginState("ada",
                new ScimLoginState("chosen-hash", 2, EARLIER, LockCause.DORMANCY, EARLIER, null)));

        loginState.dormantFixtureReset(
                ScimIdentities.withActive(fixture, fixture.profile().active(), run(0)),
                "fixture-hash", NOW, Duration.ofDays(91));

        assertThat(users.require("ada").login()).isEqualTo(new ScimLoginState(
                "fixture-hash", 0, null, null, NOW.minus(Duration.ofDays(91)), null));
    }

    // ---- a User gone under its lock ----------------------------------------------------------

    /**
     * A User deleted after its caller last saw it — just before the event's lock was granted — is
     * gone under the lock, and every event says so rather than deciding on what it no longer has.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Event.class)
    void eachEventOnAUserGoneUnderItsLockAnswersGone(Event event) {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(LIMIT - 1)));
        locks.beforeNextGrant(() -> users.deleteById(stored.id(), NOW));

        assertThat(event.report.apply(loginState, stored.id())).isEmpty();
    }

    /**
     * Nothing is written, counted or audited for a User gone under its lock — though one failure
     * short of the limit, so a failure counted on what it last held would impose a Lockout.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Event.class)
    void eachEventOnAUserGoneUnderItsLockWritesAndRecordsNothing(Event event) {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(LIMIT - 1)));
        int[] writesOnceGone = new int[1];
        locks.beforeNextGrant(() -> {
            users.deleteById(stored.id(), NOW);
            writesOnceGone[0] = users.writes();
        });

        event.report.apply(loginState, stored.id());

        assertThat(List.of(users.writes(), audit.recorded(), transaction.pending()))
                .containsExactly(writesOnceGone[0], List.of(), 0);
    }

    /**
     * An Unlock of a User serving no lockout is the administration path's to refuse: the module
     * reports that no lock stood and writes nothing, so the failure run is kept as evidence.
     */
    @Test
    void anUnlockOfAUserServingNoLockoutWritesNothing() {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(2)));
        int before = users.writes();

        loginState.unlocked(stored.id());

        assertThat(users.writes()).isEqualTo(before);
    }

    /** {@code state} stored for {@code user} by a second writer just before the next lock grant. */
    private void storeBeforeTheLock(ScimUser user, ScimLoginState state) {
        locks.beforeNextGrant(() ->
                users.given(ScimIdentities.withActive(user, user.profile().active(), state)));
    }

    // ---- an accepted Login refused on the standing found under the lock ----------------------

    /**
     * A Lockout committed while the password was being compared — after the authentication's own
     * check read the User unlocked — refuses the Login, and the lock stands: an Unlock is the only
     * exit (ADR 0007).
     */
    @ParameterizedTest(name = "{0} by a User locked for {1}")
    @MethodSource("loginsAndLockCauses")
    void aLoginByALockedUserLeavesTheLockStanding(Route route, LockCause cause, Object refusal) {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", locked(cause)));

        logIn(route, stored.id());

        assertThat(users.require("ada").login()).isEqualTo(locked(cause));
    }

    @ParameterizedTest(name = "{0} by a User locked for {1}")
    @MethodSource("loginsAndLockCauses")
    void aLoginByALockedUserIsRefusedAsLocked(Route route, LockCause cause, Object refusal) {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", locked(cause)));

        assertThat(logIn(route, stored.id()).refusal()).isEqualTo(refusal);
    }

    static Stream<Arguments> loginsAndLockCauses() {
        return Stream.of(LockCause.values()).flatMap(cause -> Stream.of(
                Arguments.of(Route.PASSWORD, cause, PasswordRefusalReason.ACCOUNT_LOCKED),
                Arguments.of(Route.EPIC, cause, StandingRefusal.LOCKED)));
    }

    @Test
    void aPasswordLoginRefusedOnTheLockSaysItWasRefused() {
        ScimUser stored = users.given(
                ScimIdentities.userWithLoginState("ada", locked(LockCause.FAILURES)));

        assertThat(loginState.passwordLogin(stored.id(), PASSWORD).orElseThrow().refused())
                .isTrue();
    }

    /** A Login refused for a Lockout hands back the state it read under the lock, unmoved. */
    @Test
    void aPasswordLoginRefusedOnTheLockHandsBackTheLockedStateUnmoved() {
        ScimUser stored = users.given(
                ScimIdentities.userWithLoginState("ada", locked(LockCause.FAILURES)));

        LoginStateChange change =
                loginState.passwordLogin(stored.id(), PASSWORD).orElseThrow().change();

        assertThat(List.of(change.after(), change.moved()))
                .containsExactly(locked(LockCause.FAILURES), Set.of());
    }

    /** An Epic Login counts toward no failure run (D12), so its refusal moves nothing. */
    @Test
    void anEpicLoginRefusedOnADeactivationHandsBackTheStateUnmoved() {
        ScimUser stored = users.given(ScimIdentities.withActive(
                ScimIdentities.userWithLoginState("ada", run(2)), false, run(2)));

        LoginStateChange change = loginState.epicLogin(stored.id()).orElseThrow().change();

        assertThat(List.of(change.after(), change.moved())).containsExactly(run(2), Set.of());
    }

    /** A deactivation refuses an Epic Login, and clears no run. */
    @Test
    void anEpicLoginByADeactivatedUserLeavesItsRun() {
        ScimUser stored = users.given(ScimIdentities.withActive(
                ScimIdentities.userWithLoginState("ada", run(2)), false, run(2)));

        loginState.epicLogin(stored.id());

        assertThat(users.require("ada").login()).isEqualTo(run(2));
    }

    /** A deactivation committed while the password was being compared refuses the Login. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("loginsByADeactivatedUser")
    void aLoginByADeactivatedUserIsRefusedAsDeactivated(Route route, Object refusal) {
        ScimUser stored = users.given(ScimIdentities.withActive(
                ScimIdentities.userWithLoginState("ada", run(2)), false, run(2)));

        assertThat(logIn(route, stored.id()).refusal()).isEqualTo(refusal);
    }

    static Stream<Arguments> loginsByADeactivatedUser() {
        return Stream.of(
                Arguments.of(Route.PASSWORD, PasswordRefusalReason.ACCOUNT_DISABLED),
                Arguments.of(Route.EPIC, StandingRefusal.DEACTIVATED));
    }

    /** Locked and deactivated at once is refused as locked, the check made first. */
    @Test
    void aLoginByAUserBothLockedAndDeactivatedIsRefusedAsLocked() {
        ScimUser stored = users.given(ScimIdentities.withActive(
                ScimIdentities.userWithLoginState("ada", locked(LockCause.FAILURES)), false,
                locked(LockCause.FAILURES)));

        assertThat(loginState.passwordLogin(stored.id(), PASSWORD).orElseThrow().refusal())
                .isEqualTo(PasswordRefusalReason.ACCOUNT_LOCKED);
    }

    /** An admissible User's Login is accepted. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Route.class)
    void aLoginByAnActiveUnlockedUserIsAccepted(Route route) {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(2)));

        assertThat(logIn(route, stored.id()).refused()).isFalse();
    }

    // ---- an accepted password Login whose credential was replaced under it -------------------

    /**
     * The stored credential is no longer the one the authentication compared the password
     * against: the Login is refused as the wrong password it now is, and counted as one.
     */
    @Test
    void aPasswordLoginWhoseCredentialWasReplacedIsRefusedAsBadCredentials() {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(2)));

        assertThat(loginState.passwordLogin(
                        stored.id(), VerifiedCredential.passwordHash("old-hash")).orElseThrow()
                .refusal())
                .isEqualTo(PasswordRefusalReason.BAD_CREDENTIALS);
    }

    /** A password removed since the comparison leaves no credential the Login verified. */
    @Test
    void aPasswordLoginWhoseCredentialWasRemovedIsRefused() {
        ScimUser stored = users.given(
                ScimIdentities.userWithLoginState("ada", passwordlessWithRun(0)));

        assertThat(loginState.passwordLogin(stored.id(), PASSWORD).orElseThrow().refused())
                .isTrue();
    }

    /** The standing is decided first, as the authentication checks it: locked over replaced. */
    @Test
    void aPasswordLoginByALockedUserWhoseCredentialWasReplacedIsRefusedAsLocked() {
        ScimUser stored = users.given(
                ScimIdentities.userWithLoginState("ada", locked(LockCause.FAILURES)));

        assertThat(loginState.passwordLogin(
                        stored.id(), VerifiedCredential.passwordHash("old-hash")).orElseThrow()
                .refusal())
                .isEqualTo(PasswordRefusalReason.ACCOUNT_LOCKED);
    }

    /** An Epic Login presented no password of ours, so whatever the credential, none was replaced. */
    @Test
    void anEpicLoginIsNotRefusedForTheStoredCredential() {
        ScimUser stored = users.given(
                ScimIdentities.userWithLoginState("ada", passwordlessWithRun(0)));

        assertThat(loginState.epicLogin(stored.id()).orElseThrow().refused()).isFalse();
    }

    // ---- a password Login's decision, its refusal counted under the same lock ----------------

    /**
     * A password Login refused under the lock for a deactivation is counted as the authentication's
     * own {@code ACCOUNT_DISABLED} refusal is: the run lengthens, on the state the refusal was
     * decided from.
     */
    @Test
    void aPasswordLoginRefusedOnADeactivationUnderTheLockIsCounted() {
        ScimUser stored = users.given(ScimIdentities.withActive(
                ScimIdentities.userWithLoginState("ada", run(1)), false, run(1)));

        PasswordLoginDecision refused =
                loginState.passwordLogin(stored.id(), PASSWORD).orElseThrow();

        assertThat(List.of(refused.refusal(), users.require("ada").login().failedLoginAttempts()))
                .containsExactly(PasswordRefusalReason.ACCOUNT_DISABLED, 2);
    }

    /** A refusal by the authentication is reported back for the reason it was refused for. */
    @Test
    void aPasswordLoginRefusedByItsAuthenticationIsReportedForItsReason() {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(1)));

        assertThat(loginState.passwordLogin(stored.id(),
                        PasswordAnswer.refused(PasswordRefusalReason.ACCOUNT_DISABLED))
                .orElseThrow().refusal())
                .isEqualTo(PasswordRefusalReason.ACCOUNT_DISABLED);
    }

    /** The refusal that reaches the limit imposes the Lockout, audited and revoking once. */
    @Test
    void aPasswordLoginRefusedUnderTheLockAtTheLimitImposesTheLockout() {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(LIMIT - 1)));

        loginState.passwordLogin(stored.id(), VerifiedCredential.passwordHash("old-hash"));

        assertThat(List.of(audit.recorded(), transaction.pending())).containsExactly(
                List.of(new Recorded(AuditOperation.LOCKOUT_SET, null, stored.id(), null)), 1);
    }

    /** An accepted password Login is neither refused nor, without the flag, confined. */
    @Test
    void anAcceptedPasswordLoginIsNotRefusedAndNotConfined() {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(2)));

        PasswordLoginDecision accepted =
                loginState.passwordLogin(stored.id(), PASSWORD).orElseThrow();

        assertThat(List.of(accepted.refused(), accepted.confined())).containsExactly(false, false);
    }

    /** The verified credential is never printed: it is a password hash. */
    @Test
    void aVerifiedPasswordNeverPrintsItsHash() {
        assertThat(VerifiedCredential.passwordHash("the-hash").toString())
                .isEqualTo("VerifiedPassword[redacted]");
    }

    /** A password Login always verified a stored credential: there is no hash-less one. */
    @Test
    void aVerifiedPasswordHasAHash() {
        assertThatNullPointerException().isThrownBy(() -> VerifiedCredential.passwordHash(null));
    }

    /** A refusal by the authentication always has the reason it is counted and recorded for. */
    @Test
    void aRefusedPasswordHasAReason() {
        assertThatNullPointerException().isThrownBy(() -> PasswordAnswer.refused(null));
    }

    /** A password Login is decided on what its authentication answered: there is no Login without. */
    @Test
    void aPasswordLoginWithNoAnswerFromItsAuthenticationIsACallersBug() {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(1)));

        assertThatNullPointerException().isThrownBy(() -> loginState.passwordLogin(stored.id(), null));
    }

    // ---- the decisions' shape ----------------------------------------------------------------

    @Test
    void aLoginIsRefusedForOneReasonOnly() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Verification(
                LoginStateChange.none(run(0)), StandingRefusal.LOCKED, true, false));
    }

    @Test
    void aLoginRefusedOnItsStandingIsNotConfined() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Verification(
                LoginStateChange.none(run(0)), StandingRefusal.LOCKED, false, true));
    }

    @Test
    void aLoginRefusedForAReplacedCredentialIsNotConfined() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Verification(
                LoginStateChange.none(run(0)), null, true, true));
    }

    @Test
    void aDecisionHandsBackWhatItMoved() {
        assertThatNullPointerException().isThrownBy(() ->
                new Verification(null, null, false, false));
    }

    @Test
    void aRefusedPasswordLoginIsNotConfined() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PasswordLoginDecision(
                LoginStateChange.none(run(0)), PasswordRefusalReason.BAD_CREDENTIALS, true));
    }

    @Test
    void aPasswordLoginDecisionHandsBackWhatItMoved() {
        assertThatNullPointerException().isThrownBy(() ->
                new PasswordLoginDecision(null, null, false));
    }

    // ---- confinement, decided with the rest of the Login ------------------------------------

    static Stream<Arguments> confinementUnderTheLock() {
        return Stream.of(
                Arguments.of(Route.PASSWORD, flaggedWithRun(0), true),
                Arguments.of(Route.PASSWORD, run(0), false),
                Arguments.of(Route.EPIC, flaggedWithRun(0), false),
                Arguments.of(Route.EPIC, run(0), false));
    }

    /**
     * Whether the session is confined is read from the state stored under the lock — the same
     * state that decides the dormancy basis — and handed back with the decision: each User here
     * shows no flag until just before the lock is granted.
     */
    @ParameterizedTest(name = "{0} Login of {1}: confined {2}")
    @MethodSource("confinementUnderTheLock")
    void anAcceptedLoginSaysWhetherItIsConfinedFromTheStoredState(
            Route route, ScimLoginState storedByThen, boolean confined) {
        ScimUser stored = users.given(ScimIdentities.userWithLoginState("ada", run(0)));
        storeBeforeTheLock(stored, storedByThen);

        assertThat(logIn(route, stored.id()).confined()).isEqualTo(confined);
    }

    @Test
    void aRefusedLoginIsNotConfined() {
        ScimUser stored = users.given(ScimIdentities.withActive(
                ScimIdentities.userWithLoginState("ada", flaggedWithRun(0)), false,
                flaggedWithRun(0)));

        assertThat(loginState.passwordLogin(stored.id(), PASSWORD).orElseThrow().confined())
                .isFalse();
    }

    // ---- the directory's events: what a SCIM write leaves ------------------------------------

    /** A write as a SCIM use case reports it: {@code active} after it, and its credential. */
    private record Write(boolean active, Credential credential) {

        static final Write REACTIVATES = new Write(true, Credential.unchanged("hash"));
        static final Write DEACTIVATES = new Write(false, Credential.unchanged("hash"));
        static final Write KEEPS_ACTIVE = new Write(true, Credential.unchanged("hash"));
        static final Write KEEPS_INACTIVE = new Write(false, Credential.unchanged("hash"));
    }

    static Stream<Arguments> directoryWrites() {
        return Stream.of(
                Arguments.of("a Reactivation resets the basis and re-dates a required change",
                        false, flaggedWithRun(2), Write.REACTIVATES,
                        new ScimLoginState("hash", 2, null, null, NOW, NOW)),
                Arguments.of("a Reactivation of a User owing nothing requires a change",
                        false, run(0), Write.REACTIVATES,
                        new ScimLoginState("hash", 0, null, null, NOW, NOW)),
                Arguments.of("a Reactivation of a credentialless User requires no change",
                        false, new ScimLoginState(null, 0, null, null, EARLIER, null),
                        new Write(true, Credential.cleared()),
                        new ScimLoginState(null, 0, null, null, NOW, null)),
                Arguments.of("a Reactivation removing the password re-dates no kept flag",
                        false, flaggedWithRun(0), new Write(true, Credential.cleared()),
                        new ScimLoginState(null, 0, null, null, NOW, EARLIER)),
                Arguments.of("a Reactivation does not lift a lock",
                        false, locked(LockCause.DORMANCY), Write.REACTIVATES,
                        new ScimLoginState("hash", LIMIT, EARLIER, LockCause.DORMANCY, NOW, NOW)),
                Arguments.of("re-asserting active over an active User resets nothing",
                        true, run(0), Write.KEEPS_ACTIVE, run(0)),
                Arguments.of("a deactivation resets nothing",
                        true, run(0), Write.DEACTIVATES, run(0)),
                Arguments.of("staying inactive resets nothing",
                        false, run(0), Write.KEEPS_INACTIVE, run(0)),
                Arguments.of("a connector-set password is stored and requires a change as of now",
                        true, run(0), new Write(true, Credential.set("connector-hash")),
                        new ScimLoginState("connector-hash", 0, null, null, EARLIER, NOW)),
                Arguments.of("a removed password is stored, and no directory write clears the flag",
                        true, flaggedWithRun(0), new Write(true, Credential.cleared()),
                        new ScimLoginState(null, 0, null, null, EARLIER, EARLIER)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("directoryWrites")
    void eachDirectoryWriteLeavesTheLoginStateItsRuleDecides(String rule, boolean storedActive,
            ScimLoginState given, Write write, ScimLoginState expected) {
        ScimUser stored = new ScimUser(UUID.randomUUID(),
                ScimIdentities.profile("ada", storedActive), given, null,
                ScimUser.INITIAL_VERSION, EARLIER, EARLIER);

        ScimLoginState written = loginState.afterReplacement(
                stored, ScimIdentities.profile("ada", write.active()), write.credential(), NOW)
                .after();

        assertThat(written).isEqualTo(expected);
    }

    static Stream<Arguments> directoryWriteMoves() {
        return Stream.of(
                Arguments.of("a Reactivation moves the basis and the flag it re-dates",
                        false, flaggedWithRun(2), Write.REACTIVATES, List.of(BASIS, FLAG)),
                Arguments.of("re-asserting active over an active User moves nothing",
                        true, run(0), Write.KEEPS_ACTIVE, List.of()),
                Arguments.of("a connector-set password moves the flag, never the credential",
                        true, run(0), new Write(true, Credential.set("connector-hash")),
                        List.of(FLAG)),
                Arguments.of("a removed password moves no login-state column",
                        true, flaggedWithRun(0), new Write(true, Credential.cleared()),
                        List.of()));
    }

    /** A replacement hands back what it moved, in the shape every other transition does. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("directoryWriteMoves")
    void eachDirectoryWriteHandsBackExactlyThePathsItMoved(String rule, boolean storedActive,
            ScimLoginState given, Write write, List<AuditLoginStateAttribute> paths) {
        ScimUser stored = new ScimUser(UUID.randomUUID(),
                ScimIdentities.profile("ada", storedActive), given, null,
                ScimUser.INITIAL_VERSION, EARLIER, EARLIER);

        LoginStateChange change = loginState.afterReplacement(
                stored, ScimIdentities.profile("ada", write.active()), write.credential(), NOW);

        assertThat(change.moved()).containsExactlyInAnyOrderElementsOf(paths);
    }

    /** A connector chose and transported the first password, so the User must replace it. */
    @Test
    void aUserCreatedWithAPasswordOwesAChangeAsOfItsCreation() {
        assertThat(loginState.created("hash", NOW).after())
                .isEqualTo(new ScimLoginState("hash", 0, null, null, null, NOW));
    }

    /** The change a creation with a password owes is the one path it moves. */
    @Test
    void aUserCreatedWithAPasswordMovesTheFlagAlone() {
        assertThat(loginState.created("hash", NOW).moved()).containsExactly(FLAG);
    }

    /** A credentialless creation decides nothing beyond the new User's own state. */
    @Test
    void aUserCreatedWithoutAPasswordMovesNothing() {
        assertThat(loginState.created(null, NOW).moved()).isEmpty();
    }

    /** The flag arrives with the first password, so a credentialless User owes none. */
    @Test
    void aUserCreatedWithoutAPasswordOwesNothing() {
        assertThat(loginState.created(null, NOW).after()).isEqualTo(ScimLoginState.CREDENTIALLESS);
    }

    /**
     * The development seed puts the dormant fixture back on every startup: the fixture password
     * with no change owed, no run and no lock, and its basis backdated so the startup run locks it.
     */
    @Test
    void theDormantFixtureIsResetToDormant() {
        ScimUser fixture = users.given(ScimIdentities.userWithLoginState("ada",
                new ScimLoginState("chosen-hash", 2, EARLIER, LockCause.DORMANCY, EARLIER, EARLIER)));

        loginState.dormantFixtureReset(fixture, "fixture-hash", NOW, Duration.ofDays(91));

        assertThat(users.require("ada").login()).isEqualTo(new ScimLoginState(
                "fixture-hash", 0, null, null, NOW.minus(Duration.ofDays(91)), null));
    }

    /** The fixture's reset hands back everything it moved: the run, the lock, basis and flag. */
    @Test
    void theDormantFixtureResetHandsBackEveryPathItMoved() {
        ScimUser fixture = users.given(ScimIdentities.userWithLoginState("ada",
                new ScimLoginState("chosen-hash", 2, EARLIER, LockCause.DORMANCY, EARLIER, EARLIER)));

        LoginStateChange reset =
                loginState.dormantFixtureReset(fixture, "fixture-hash", NOW, Duration.ofDays(91));

        assertThat(reset.moved())
                .containsExactlyInAnyOrder(RUN, LOCKED_AT, LOCK_CAUSE, BASIS, FLAG);
    }

    /**
     * A fixture gone by the reset's lock has nothing written, and the reset answers the seed's
     * own copy of it, unmoved.
     */
    @Test
    void theDormantFixtureResetOfAFixtureGoneUnderItsLockAnswersItUnmoved() {
        ScimUser fixture = users.given(ScimIdentities.userWithLoginState("ada", run(2)));
        locks.beforeNextGrant(() -> users.deleteById(fixture.id(), NOW));

        LoginStateChange reset =
                loginState.dormantFixtureReset(fixture, "fixture-hash", NOW, Duration.ofDays(91));

        assertThat(reset).isEqualTo(LoginStateChange.none(run(2)));
    }

    /** The fixture's credential is replaced by a completed password change, as of now. */
    @Test
    void theDormantFixtureResetCompletesAPasswordChangeAsOfNow() {
        ScimUser fixture = users.given(ScimIdentities.userWithLoginState("ada", run(0)));

        LoginStateChange reset =
                loginState.dormantFixtureReset(fixture, "fixture-hash", NOW, Duration.ofDays(91));

        assertThat(reset.completedAt()).isEqualTo(NOW);
    }

    // ---- what a newly imposed Lockout implies ------------------------------------------------

    @Test
    void imposingTheLockoutRecordsOneLockoutSetAgainstTheUser() {
        UUID ada = givenAda();

        countTimes(LIMIT);

        assertThat(audit.recorded()).containsExactly(
                new Recorded(AuditOperation.LOCKOUT_SET, null, ada, null));
    }

    @Test
    void aFailureBelowTheLimitAuditsAndRevokesNothing() {
        UUID ada = givenAda();

        countTimes(LIMIT - 1);
        transaction.commit();

        assertThat(audit.recorded()).isEmpty();
        assertThat(sessions.sessionsOf(ada)).containsExactly("ada-session");
    }

    /**
     * A lock that left live sessions alone would close the front door while the User kept acting
     * through a session it already held — and the revocation waits for the commit, because Redis
     * is not in the transaction (ADR 0002).
     */
    @Test
    void imposingTheLockoutRevokesEverySessionOnlyOnceTheTransactionCommits() {
        UUID ada = givenAda();
        sessions.open(ada, "ada-other-session");

        countTimes(LIMIT);
        assertThat(sessions.sessionsOf(ada)).as("before the commit").hasSize(2);
        transaction.commit();

        assertThat(sessions.sessionsOf(ada)).isEmpty();
    }

    /** The Lockout's revocation is audited like every other, under its own cause. */
    @Test
    void imposingTheLockoutAuditsTheRevocationUnderTheFailureRunLockout() {
        UUID ada = givenAda();

        countTimes(LIMIT);
        transaction.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE)).containsExactly(new Recorded(
                AuditOperation.USER_SESSIONS_REVOKE, null, ada, "SUCCESS::FAILURE_RUN_LOCKOUT"));
    }

    /** A rolled-back transaction wrote no lock, so it revokes nothing. */
    @Test
    void aRolledBackLockoutRevokesNothing() {
        UUID ada = givenAda();

        countTimes(LIMIT);
        transaction.rollback();

        assertThat(sessions.sessionsOf(ada)).containsExactly("ada-session");
    }

    /**
     * The order the counting paths rely on: the new state is written, then the Lockout is audited,
     * then the revocation is handed to the commit — so a path's own refusal, recorded after the
     * event returns, lands last. Observed from inside the hand-over, which is the last of the three.
     */
    @Test
    void theStateIsWrittenAndAuditedBeforeTheRevocationIsScheduled() {
        givenAda();
        List<String> seenAtHandOver = new ArrayList<>();
        loginState = moduleDeferringTo(work -> {
            seenAtHandOver.add("locked=" + users.require("ada").login().isLocked());
            seenAtHandOver.add("audited=" + audit.of(AuditOperation.LOCKOUT_SET).size());
            transaction.run(work);
        });

        countTimes(LIMIT);

        assertThat(seenAtHandOver).containsExactly("locked=true", "audited=1");
    }

    /** Only the transition into the lock is audited and revokes; a failure after it does neither. */
    @Test
    void aFailureOnAnAlreadyLockedUserImposesNothingAgain() {
        givenAda();
        countTimes(LIMIT);
        transaction.commit();
        audit.reset();

        countTimes(1);

        assertThat(audit.recorded()).as("no second LOCKOUT_SET").isEmpty();
        assertThat(transaction.pending()).as("no second revocation").isZero();
    }

    /** A decision that changes neither the run nor the lock writes neither. */
    @Test
    void aFailureOnAnAlreadyLockedUserWritesNothing() {
        users.given(ScimIdentities.userWithLoginState("ada", locked(LockCause.FAILURES)));
        int writesBefore = users.writes();

        countTimes(1);

        assertThat(users.writes()).isEqualTo(writesBefore);
    }

    // ---- the dormancy lock, and what imposing it implies -------------------------------------

    /** The job counts and logs only a lock it imposed, so the module says whether it did. */
    @ParameterizedTest(name = "a User locked for {0} before the job reports {1}")
    @CsvSource({"NOTHING, true", "FAILURES, false", "DORMANCY, false"})
    void theDormancyLockReportsWhetherItImposedOne(String before, boolean imposed) {
        ScimLoginState given = before.equals("NOTHING") ? run(0) : locked(LockCause.valueOf(before));
        ScimUser user = users.given(ScimIdentities.userWithLoginState("ada", given));

        assertThat(loginState.dormancyLocked(user.id(), NOW).orElseThrow().hasMoved(LOCKED_AT))
                .isEqualTo(imposed);
    }

    /** A dormancy lock it imposed is recorded once, actorless, naming the User. */
    @Test
    void imposingTheDormancyLockRecordsOneDormancyLockoutAgainstTheUser() {
        UUID ada = givenAda();

        loginState.dormancyLocked(ada, NOW);

        assertThat(audit.recorded()).containsExactly(
                new Recorded(AuditOperation.DORMANCY_LOCKOUT, null, ada, null));
    }

    /** Its row names what the lock moved: the lock and its cause, never the run. */
    @Test
    void theDormancyLockoutNamesTheLockItSet() {
        users.given(ScimIdentities.userWithLoginState("ada", flaggedWithRun(2)));

        loginState.dormancyLocked(users.require("ada").id(), NOW);

        assertThat(audit.changedPaths(AuditOperation.DORMANCY_LOCKOUT))
                .containsExactly(List.of("lockedAt", "lockCause"));
    }

    /** The dormancy lock ends every Session too, once the transaction commits (ADR 0002). */
    @Test
    void imposingTheDormancyLockRevokesEverySessionOnlyOnceTheTransactionCommits() {
        UUID ada = givenAda();
        sessions.open(ada, "ada-other-session");

        loginState.dormancyLocked(ada, NOW);
        assertThat(sessions.sessionsOf(ada)).as("before the commit").hasSize(2);
        transaction.commit();

        assertThat(sessions.sessionsOf(ada)).isEmpty();
    }

    /** Its revocation is audited under its own cause. */
    @Test
    void imposingTheDormancyLockAuditsTheRevocationUnderTheDormancyLockout() {
        UUID ada = givenAda();

        loginState.dormancyLocked(ada, NOW);
        transaction.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE)).containsExactly(new Recorded(
                AuditOperation.USER_SESSIONS_REVOKE, null, ada, "SUCCESS::DORMANCY_LOCKOUT"));
    }

    /**
     * The store's compare-and-set says whether the lock was imposed, not the module's read: a
     * failure lock stored after the read and before the write is kept, and the module carries out
     * nothing for a lock it did not impose.
     */
    @Test
    void aDormancyLockTheStoreRefusesToStoreRecordsAndRevokesNothing() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", run(0)));
        users.beforeNextLockImposed(() -> users.given(
                ScimIdentities.withActive(ada, true, locked(LockCause.FAILURES))));

        LoginStateChange lock = loginState.dormancyLocked(ada.id(), NOW).orElseThrow();

        assertThat(List.of(lock.moved(), audit.recorded(), transaction.pending()))
                .containsExactly(Set.of(), List.of(), 0);
    }

    /** A User already locked keeps its lock, and nothing is recorded or revoked for it. */
    @ParameterizedTest(name = "a User locked for {0}")
    @EnumSource(LockCause.class)
    void theDormancyLockOfALockedUserRecordsAndRevokesNothing(LockCause cause) {
        users.given(ScimIdentities.userWithLoginState("ada", locked(cause)));

        loginState.dormancyLocked(users.require("ada").id(), NOW);

        assertThat(List.of(audit.recorded(), transaction.pending())).containsExactly(List.of(), 0);
    }

    /**
     * A lock the login path imposed after the job's read is kept with its cause: the module
     * decides from the stored state, and the store refuses to overwrite a lock that stands.
     */
    @Test
    void aFailureLockImposedAfterTheJobsReadIsKeptWithItsCause() {
        ScimUser read = users.given(ScimIdentities.userWithLoginState("ada", run(LIMIT - 1)));
        loginState.passwordChangeFailed(read.id());

        LoginStateChange lock =
                loginState.dormancyLocked(read.id(), NOW.plusSeconds(1)).orElseThrow();

        assertThat(lock.moved()).isEmpty();
        assertThat(users.require("ada").login()).isEqualTo(
                new ScimLoginState("hash", LIMIT, NOW, LockCause.FAILURES, EARLIER, null));
    }

    @Test
    void theBootstrapAdminsLockoutFreeRunImposesNoLockoutEvents() {
        ScimUser recovery = users.createReserved(
                ScimIdentities.user("ada"), ReservedResourceName.BOOTSTRAP_ADMIN);
        sessions.open(recovery.id(), "recovery-session");

        countTimes(LIMIT * 3);
        transaction.commit();

        assertThat(audit.recorded()).isEmpty();
        assertThat(sessions.sessionsOf(recovery.id())).containsExactly("recovery-session");
    }

    /**
     * The exemption is keyed on the reservation marker, not on a name. Arranged so a name
     * comparison would get it exactly backwards: the ORDINARY User carries the name a
     * configured-string exemption would have matched, and the reserved one carries a name nothing
     * could have been configured with.
     */
    @Test
    void theExemptionFollowsTheReservationMarkerAndNotTheUserName() {
        users.given(ScimIdentities.user("recovery-admin"));
        users.createReserved(
                ScimIdentities.user("someone-else"), ReservedResourceName.BOOTSTRAP_ADMIN);

        for (int attempt = 0; attempt < LIMIT; attempt++) {
            loginState.passwordChangeFailed(users.require("recovery-admin").id());
            loginState.passwordChangeFailed(users.require("someone-else").id());
        }

        assertThat(List.of(
                users.require("recovery-admin").login().isLocked(),
                users.require("someone-else").login().isLocked()))
                .containsExactly(true, false);
    }

    /**
     * A failure run is not a SCIM attribute, so writing one goes through the narrow login-state
     * write and must not move the resource's ETag: a mistyped password cannot invalidate every
     * cached copy of the User.
     */
    @Test
    void countingDoesNotAdvanceTheResourceVersion() {
        givenAda();
        long version = users.require("ada").version();

        countTimes(LIMIT);

        assertThat(users.require("ada").version()).isEqualTo(version);
    }

    // ---- what the events answer --------------------------------------------------------------

    /** Whether the Unlock required a change, which the administration path audits. */
    @ParameterizedTest(name = "locked={0}, password={1} -> change required {2}")
    @CsvSource({"true, true, true", "true, false, false", "false, true, false"})
    void anUnlockRequiresAChangeOnlyWhenItLiftsALockFromACredentialedUser(
            boolean lockedBefore, boolean withPassword, boolean changeRequired) {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", new ScimLoginState(
                withPassword ? "hash" : null, LIMIT,
                lockedBefore ? EARLIER : null, lockedBefore ? LockCause.FAILURES : null,
                EARLIER, null)));

        assertThat(loginState.unlocked(ada.id()).orElseThrow().hasMoved(FLAG))
                .isEqualTo(changeRequired);
    }

    /**
     * What the Unlock changed, which its {@code LOCKOUT_LIFT} records: the lock it lifted, if one
     * stood, and whether the failure run held attempts to clear — one attempt being enough. With
     * no lock standing nothing is cleared, the run's attempts included.
     */
    @ParameterizedTest(name = "attempts={0}, lock={1}, password={2} -> lifted {3}, cleared {4}")
    @CsvSource({
            "5, FAILURES, true, FAILURES, true",
            "0, DORMANCY, true, DORMANCY, false",
            "5, FAILURES, false, FAILURES, true",
            "1, , true, , false",
            "0, , true, , false"})
    void anUnlockReportsTheLockItLiftedAndWhetherItClearedAttempts(int attempts,
            LockCause lockBefore, boolean withPassword, LockCause lifted, boolean cleared) {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", new ScimLoginState(
                withPassword ? "hash" : null, attempts, lockBefore == null ? null : EARLIER,
                lockBefore, EARLIER, null)));

        LoginStateChange decided = loginState.unlocked(ada.id()).orElseThrow();

        assertThat(List.of(Optional.ofNullable(decided.before().lockCause()), decided.hasMoved(RUN)))
                .containsExactly(Optional.ofNullable(lifted), cleared);
    }

    /** The one instant the change was completed at, which the caller records the history under. */
    @Test
    void aPasswordChangeAnswersWithTheInstantItWasCompletedAt() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", run(0)));

        assertThat(loginState.passwordChanged(ada.id(), "new-hash").orElseThrow().completedAt())
                .isEqualTo(NOW);
    }

    /** A completed change says so: its row names the credential from that. */
    @Test
    void aPasswordChangeSaysItCompletedOne() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", run(0)));

        assertThat(loginState.passwordChanged(ada.id(), "new-hash").orElseThrow()
                .completedPasswordChange()).isTrue();
    }

    /** A User gone between the read under the lock and the completion is gone too. */
    @Test
    void aPasswordChangeOfAUserVanishedByTheCompletionAnswersGone() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", run(0)));
        users.vanishBeforeNextPasswordChange(ada.id());

        assertThat(loginState.passwordChanged(ada.id(), "new-hash")).isEmpty();
    }

    /** ... and records no use of the account. */
    @Test
    void aPasswordChangeOfAVanishedUserMovesNoBasis() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", run(0)));
        users.vanishBeforeNextPasswordChange(ada.id());

        loginState.passwordChanged(ada.id(), "new-hash");

        assertThat(users.require("ada").login().lastAuthenticatedAt()).isEqualTo(EARLIER);
    }

    /** Confinement, the one rule both the basis and the issued authorities read. */
    @ParameterizedTest(name = "{0} Login, flag set={1} -> confined {2}")
    @CsvSource({"PASSWORD, true, true", "PASSWORD, false, false",
            "EPIC, true, false", "EPIC, false, false"})
    void onlyAPasswordLoginByAFlaggedUserIsConfined(
            Route login, boolean flagged, boolean confined) {
        ScimLoginState state = flagged ? flaggedWithRun(0) : run(0);

        assertThat(verifiedBy(login).confines(state)).isEqualTo(confined);
    }

    private LoginStateService moduleDeferringTo(AfterCommit afterCommit) {
        return new LoginStateService(
                users,
                users,
                locks,
                new SessionRevocationService(
                        sessions, afterCommit, audit, new RecordingOperationalAlerts()),
                new LockoutPolicy(LIMIT),
                audit,
                clock);
    }

    private UUID givenAda() {
        UUID ada = users.given(ScimIdentities.user("ada")).id();
        sessions.open(ada, "ada-session");
        return ada;
    }

    /** Counts {@code times} wrong current passwords against ada, by her stable id. */
    private void countTimes(int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            loginState.passwordChangeFailed(users.require("ada").id());
        }
    }
}
