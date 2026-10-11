package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.application.LoginStateService.LoginKind;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The login-state module: what each Login-side event — a counted failure, a verified credential,
 * a completed password change, an Unlock — leaves stored as the User's login state, and what a
 * newly imposed Lockout implies: one {@code LOCKOUT_SET} and one revocation of every Session, after
 * the commit.
 *
 * <p>The paths that report the events are tested for what is theirs alone (their refusal event,
 * their response, the order their refusal lands in) in {@link LoginAttemptServiceTests},
 * {@link PasswordChangeServiceTests} and {@link IdentityAdministrationServiceTests}; nothing about
 * the rules themselves is restated there.
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

    private LoginStateService loginState = moduleDeferringTo(transaction);

    // ---- the rules, one row per case ---------------------------------------------------------

    /** An event as its caller reports it. */
    private enum Event {
        FAILURE_COUNTED((module, user) -> module.failureCounted(user)),
        PASSWORD_LOGIN((module, user) -> module.credentialVerified(user, LoginKind.PASSWORD)),
        EPIC_LOGIN((module, user) -> module.credentialVerified(user, LoginKind.EPIC)),
        PASSWORD_CHANGED((module, user) -> module.passwordChanged(user, "new-hash")),
        UNLOCKED((module, user) -> module.unlocked(user));

        private final BiConsumer<LoginStateService, ScimUser> report;

        Event(BiConsumer<LoginStateService, ScimUser> report) {
            this.report = report;
        }
    }

    private static ScimLoginState run(int failures) {
        return new ScimLoginState("hash", failures, null, null, EARLIER, null);
    }

    private static ScimLoginState flaggedWithRun(int failures) {
        return new ScimLoginState("hash", failures, null, null, EARLIER, EARLIER);
    }

    private static ScimLoginState locked(LockCause cause) {
        return new ScimLoginState("hash", LIMIT, EARLIER, cause, EARLIER, null);
    }

    static Stream<Arguments> rules() {
        return Stream.of(
                row("a failure below the limit lengthens the run and locks nothing",
                        Event.FAILURE_COUNTED, false, run(1),
                        new ScimLoginState("hash", 2, null, null, EARLIER, null)),
                row("the failure reaching the limit locks for failures as of now",
                        Event.FAILURE_COUNTED, false, run(LIMIT - 1),
                        new ScimLoginState("hash", LIMIT, NOW, LockCause.FAILURES, EARLIER, null)),
                row("a failure on a User locked for failures neither deepens nor restarts it",
                        Event.FAILURE_COUNTED, false, locked(LockCause.FAILURES),
                        locked(LockCause.FAILURES)),
                row("a failure on a User locked for dormancy keeps the dormancy cause",
                        Event.FAILURE_COUNTED, false, locked(LockCause.DORMANCY),
                        locked(LockCause.DORMANCY)),
                row("the Bootstrap Admin's failure at the limit is counted and never locks",
                        Event.FAILURE_COUNTED, true, run(LIMIT - 1),
                        new ScimLoginState("hash", LIMIT, null, null, EARLIER, null)),
                row("a password Login clears the run and moves the dormancy basis",
                        Event.PASSWORD_LOGIN, false, run(2),
                        new ScimLoginState("hash", 0, null, null, NOW, null)),
                row("a confined Login does not move the dormancy basis",
                        Event.PASSWORD_LOGIN, false, flaggedWithRun(2),
                        new ScimLoginState("hash", 0, null, null, EARLIER, EARLIER)),
                row("an Epic Login is never confined: it moves the basis and leaves the flag set",
                        Event.EPIC_LOGIN, false, flaggedWithRun(2),
                        new ScimLoginState("hash", 0, null, null, NOW, EARLIER)),
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
                row("an Unlock of an unlocked User clears its run and nothing else",
                        Event.UNLOCKED, false, run(2),
                        new ScimLoginState("hash", 0, null, null, EARLIER, null)));
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

        event.report.accept(loginState, stored);

        assertThat(users.require("ada").login()).isEqualTo(expected);
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

    /**
     * No passage of time is a lift (ADR 0007). The clock moves a decade so the assertion could not
     * pass against a merely long window.
     */
    @Test
    void aFailureLongAfterTheLockoutNeitherLiftsNorRestartsIt() {
        givenAda();
        countTimes(LIMIT);

        clock.advanceBy(Duration.ofDays(3650));
        countTimes(1);

        assertThat(users.require("ada").login().lockedAt()).isEqualTo(NOW);
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
            loginState.failureCounted(users.require("recovery-admin"));
            loginState.failureCounted(users.require("someone-else"));
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

    /** The Unlock response is built from the decision, so the decision is the state written. */
    @Test
    void anUnlockAnswersWithTheStateItLeftStored() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState(
                "ada", locked(LockCause.FAILURES)));

        LoginStateService.Unlock decided = loginState.unlocked(ada);

        assertThat(decided.state()).isEqualTo(users.require("ada").login());
    }

    /** Whether the Unlock required a change, which the administration path audits. */
    @ParameterizedTest(name = "locked={0}, password={1} -> change required {2}")
    @CsvSource({"true, true, true", "true, false, false", "false, true, false"})
    void anUnlockRequiresAChangeOnlyWhenItLiftsALockFromACredentialedUser(
            boolean lockedBefore, boolean withPassword, boolean changeRequired) {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", new ScimLoginState(
                withPassword ? "hash" : null, LIMIT,
                lockedBefore ? EARLIER : null, lockedBefore ? LockCause.FAILURES : null,
                EARLIER, null)));

        assertThat(loginState.unlocked(ada).changeRequired()).isEqualTo(changeRequired);
    }

    /** The one instant the change was completed at, which the caller records the history under. */
    @Test
    void aPasswordChangeAnswersWithTheInstantItWasCompletedAt() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", run(0)));

        assertThat(loginState.passwordChanged(ada, "new-hash")).contains(NOW);
    }

    /** A User gone between the read and the write completes no change. */
    @Test
    void aPasswordChangeOfAVanishedUserAnswersThatNoChangeWasCompleted() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", run(0)));
        users.vanishBeforeNextPasswordChange(ada.id());

        assertThat(loginState.passwordChanged(ada, "new-hash")).isEmpty();
    }

    /** ... and records no use of the account. */
    @Test
    void aPasswordChangeOfAVanishedUserMovesNoBasis() {
        ScimUser ada = users.given(ScimIdentities.userWithLoginState("ada", run(0)));
        users.vanishBeforeNextPasswordChange(ada.id());

        loginState.passwordChanged(ada, "new-hash");

        assertThat(users.require("ada").login().lastAuthenticatedAt()).isEqualTo(EARLIER);
    }

    /** Confinement, the one rule both the basis and the issued authorities read. */
    @ParameterizedTest(name = "{0} Login, flag set={1} -> confined {2}")
    @CsvSource({"PASSWORD, true, true", "PASSWORD, false, false",
            "EPIC, true, false", "EPIC, false, false"})
    void onlyAPasswordLoginByAFlaggedUserIsConfined(
            LoginKind login, boolean flagged, boolean confined) {
        ScimLoginState state = flagged ? flaggedWithRun(0) : run(0);

        assertThat(LoginStateService.confines(login, state)).isEqualTo(confined);
    }

    private LoginStateService moduleDeferringTo(AfterCommit afterCommit) {
        return new LoginStateService(
                users,
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

    /** Counts {@code times} failures against ada, re-reading the User each time as a path does. */
    private void countTimes(int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            loginState.failureCounted(users.require("ada"));
        }
    }
}
