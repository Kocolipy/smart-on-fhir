package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one step that carries out a counted failure, whichever path counted it: what is persisted,
 * and what a newly imposed Lockout implies — one {@code LOCKOUT_SET} and one revocation of every
 * Session, after the commit.
 *
 * <p>The paths that call it are tested for what is theirs alone (their refusal event, their
 * response, the order their refusal lands in) in {@link LoginAttemptServiceTests} and
 * {@link PasswordChangeServiceTests}; nothing about the Lockout itself is restated there.
 */
class FailureCounterTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    /** Far past any window the former expiring lockout could have had. */
    private static final Duration A_LONG_TIME = Duration.ofDays(3650);

    private static final int LIMIT = 3;

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private FailureCounter failures;
    private UUID ada;

    @BeforeEach
    void setUp() {
        failures = counterDeferringTo(transaction);
        ada = users.given(ScimIdentities.user("ada")).id();
        sessions.open(ada, "ada-session");
    }

    // ---- below the threshold -----------------------------------------------------------------

    @Test
    void aFailureBelowTheThresholdIsCountedAndImposesNothing() {
        countTimes("ada", LIMIT - 1);
        transaction.commit();

        ScimUser after = users.require("ada");
        assertThat(after.login().failedLoginAttempts()).isEqualTo(LIMIT - 1);
        assertThat(after.login().isLocked()).isFalse();
        assertThat(after.login().lockedAt()).isNull();
        assertThat(audit.recorded()).as("no Lockout event below the threshold").isEmpty();
        assertThat(sessions.revocations()).isEmpty();
        assertThat(sessions.sessionsOf(ada)).containsExactly("ada-session");
    }

    /**
     * A failure run is not a SCIM attribute, so writing one goes through the narrow login-state
     * write and must not move the resource's ETag: a mistyped password cannot invalidate every
     * cached copy of the User.
     */
    @Test
    void countingDoesNotAdvanceTheResourceVersion() {
        ScimUser before = users.require("ada");
        clock.advanceBy(Duration.ofHours(1));

        countTimes("ada", LIMIT);

        ScimUser after = users.require("ada");
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.lastModifiedAt()).isEqualTo(before.lastModifiedAt());
    }

    // ---- the failure that imposes the Lockout ------------------------------------------------

    @Test
    void theFailureReachingTheThresholdLocksTheUserAsOfNowForFailures() {
        countTimes("ada", LIMIT);

        ScimUser locked = users.require("ada");
        assertThat(locked.login().isLocked()).isTrue();
        assertThat(locked.login().lockedAt()).isEqualTo(NOW);
        assertThat(locked.login().lockCause()).isEqualTo(LockCause.FAILURES);
        assertThat(locked.login().failedLoginAttempts()).isEqualTo(LIMIT);
    }

    @Test
    void imposingTheLockoutRecordsOneLockoutSetAgainstTheUser() {
        countTimes("ada", LIMIT);

        assertThat(audit.recorded()).containsExactly(
                new Recorded(AuditOperation.LOCKOUT_SET, null, ada, null));
    }

    /**
     * A lock that left live sessions alone would close the front door while the User kept acting
     * through a session it already held — and the revocation waits for the commit, because Redis
     * is not in the transaction (ADR 0002).
     */
    @Test
    void imposingTheLockoutRevokesEverySessionOnceAfterTheCommit() {
        sessions.open(ada, "ada-other-session");

        countTimes("ada", LIMIT);

        assertThat(transaction.pending()).isEqualTo(1);
        assertThat(sessions.revocations()).as("before the commit").isEmpty();
        assertThat(sessions.sessionsOf(ada)).hasSize(2);

        transaction.commit();

        assertThat(sessions.revocations()).containsExactly(ada);
        assertThat(sessions.sessionsOf(ada)).isEmpty();
    }

    /** The Lockout's revocation is audited like every other, under its own cause. */
    @Test
    void imposingTheLockoutAuditsTheRevocationUnderTheFailureRunLockout() {
        countTimes("ada", LIMIT);
        transaction.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE)).containsExactly(new Recorded(
                AuditOperation.USER_SESSIONS_REVOKE, null, ada, "SUCCESS::FAILURE_RUN_LOCKOUT"));
    }

    /** A rolled-back transaction wrote no lock, so it revokes nothing. */
    @Test
    void aRolledBackLockoutRevokesNothing() {
        countTimes("ada", LIMIT);
        transaction.rollback();

        assertThat(sessions.revocations()).isEmpty();
        assertThat(sessions.sessionsOf(ada)).containsExactly("ada-session");
    }

    /**
     * The order the paths rely on: the new state is persisted, then the Lockout is audited, then
     * the revocation is handed to the commit — so a path's own refusal, recorded after the step
     * returns, lands last. Observed from inside the hand-over, which is the last of the three.
     */
    @Test
    void theStateIsPersistedAndAuditedBeforeTheRevocationIsScheduled() {
        List<String> seenAtHandOver = new ArrayList<>();
        failures = counterDeferringTo(work -> {
            ScimUser stored = users.require("ada");
            seenAtHandOver.add("locked=" + stored.login().isLocked());
            seenAtHandOver.add("audited=" + audit.of(AuditOperation.LOCKOUT_SET).size());
            transaction.run(work);
        });

        countTimes("ada", LIMIT);

        assertThat(seenAtHandOver).containsExactly("locked=true", "audited=1");
    }

    // ---- a failure on a User already locked --------------------------------------------------

    /** Only the transition into the lock is audited and revokes; a failure after it does neither. */
    @Test
    void aFailureOnAnAlreadyLockedUserImposesNothingAgain() {
        countTimes("ada", LIMIT);
        transaction.commit();
        audit.reset();

        countTimes("ada", 1);

        assertThat(audit.recorded()).as("no second LOCKOUT_SET").isEmpty();
        assertThat(transaction.pending()).as("no second revocation").isZero();
        assertThat(sessions.revocations()).containsExactly(ada);
    }

    /**
     * Neither deepened nor restarted, and no passage of time is a lift (ADR 0007). The clock moves
     * a decade so the assertion could not pass against a merely long window.
     */
    @Test
    void aFailureLongAfterTheLockoutNeitherLiftsNorDeepensIt() {
        countTimes("ada", LIMIT);

        clock.advanceBy(A_LONG_TIME);
        countTimes("ada", 1);

        ScimUser stillLocked = users.require("ada");
        assertThat(stillLocked.login().isLocked()).isTrue();
        assertThat(stillLocked.login().lockedAt()).isEqualTo(NOW);
        assertThat(stillLocked.login().failedLoginAttempts()).isEqualTo(LIMIT);
    }

    // ---- the Bootstrap Admin, counted and never locked ---------------------------------------

    @Test
    void theBootstrapAdminsFailuresAreCountedAndNeverLock() {
        ScimUser recovery = givenBootstrapAdmin("recovery-admin");
        sessions.open(recovery.id(), "recovery-session");

        countTimes("recovery-admin", LIMIT * 3);
        transaction.commit();

        ScimUser after = users.require("recovery-admin");
        assertThat(after.login().failedLoginAttempts()).isEqualTo(LIMIT * 3);
        assertThat(after.login().isLocked()).isFalse();
        assertThat(after.login().lockedAt()).isNull();
        assertThat(audit.of(AuditOperation.LOCKOUT_SET)).isEmpty();
        assertThat(sessions.revocations()).isEmpty();
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
        givenBootstrapAdmin("someone-else");

        countTimes("recovery-admin", LIMIT);
        countTimes("someone-else", LIMIT * 3);

        assertThat(users.require("recovery-admin").login().isLocked()).isTrue();
        assertThat(users.require("someone-else").login().isLocked()).isFalse();
        assertThat(users.require("someone-else").login().failedLoginAttempts())
                .isEqualTo(LIMIT * 3);
    }

    private FailureCounter counterDeferringTo(AfterCommit afterCommit) {
        return new FailureCounter(
                users,
                new SessionRevocationService(
                        sessions,
                        afterCommit,
                        audit,
                        new RecordingOperationalAlerts()),
                new LockoutPolicy(LIMIT),
                audit,
                clock);
    }

    /** Counts {@code times} failures, re-reading the User each time as a path does. */
    private void countTimes(String userName, int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            failures.count(users.require(userName));
        }
    }

    /**
     * The reservation is applied through the port, because production has no other way to
     * produce one: there is deliberately no factory that mints a reserved resource.
     */
    private ScimUser givenBootstrapAdmin(String userName) {
        return users.createReserved(
                ScimIdentities.user(userName), ReservedResourceName.BOOTSTRAP_ADMIN);
    }
}
