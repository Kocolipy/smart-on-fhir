package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimUser;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Counting login attempts against the one login identity there is: a SCIM User.
 *
 * <p>The failure run and the lock instant live in {@code ScimLoginState} and are written
 * through the port's narrow login-state operation, so every assertion about them is read
 * back off {@code user.login()} rather than off a row of its own — there is no accounts
 * table any more.
 */
class LoginAttemptServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    /** Far past any window the former expiring lockout could have had. */
    private static final Duration A_LONG_TIME = Duration.ofDays(3650);

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private LoginAttemptService attempts;

    @BeforeEach
    void setUp() {
        attempts = new LoginAttemptService(
                users, sessions, transaction, new LockoutPolicy(3), audit, clock);
        users.given(ScimIdentities.user("ada"));
    }

    @Test
    void aRefusedAttemptIsCountedAgainstTheIdentity() {
        attempts.recordFailure("ada", AuditRefusalReason.BAD_CREDENTIALS);

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(1);
        assertThat(users.require("ada").login().isLocked()).isFalse();
    }

    @Test
    void anAcceptedLoginResetsTheFailureCount() {
        failTimes(2);

        attempts.recordPasswordSuccess("ada", null);

        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
        assertThat(users.require("ada").login().lockedAt()).isNull();
    }

    /** A password Login's {@code LOGIN_SUCCESS} names method {@code password} and no factor. */
    @Test
    void aPasswordSuccessIsRecordedUnderPasswordWithNoFactor() {
        attempts.recordPasswordSuccess("ada", null);

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(Recorded::subjectId)
                .containsExactly(users.require("ada").id());
        assertThat(audit.loginMethods()).containsExactly(AuditLoginMethod.PASSWORD);
        assertThat(audit.mfaFactors()).containsExactly((AuditMfaFactor) null);
    }

    /**
     * An Epic Login's success is recorded as a password Login's is — failure run cleared, other
     * sessions revoked — under method {@code sso} with its MFA factor (D15, D17).
     */
    @Test
    void anEpicSuccessIsRecordedUnderSsoWithItsFactor() {
        failTimes(2);
        UUID ada = users.require("ada").id();
        sessions.open(ada, "ada-current");
        sessions.open(ada, "ada-elsewhere");
        audit.reset();

        attempts.recordEpicSuccess("ada", "ada-current", AuditMfaFactor.OTP);
        transaction.commit();

        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
        assertThat(sessions.sessionsOf(ada)).containsExactly("ada-current");
        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(Recorded::subjectId)
                .containsExactly(ada);
        assertThat(audit.loginMethods()).containsExactly(AuditLoginMethod.SSO);
        assertThat(audit.mfaFactors()).containsExactly(AuditMfaFactor.OTP);
    }

    /**
     * A refused Epic Login is recorded against the User it named and counts toward no failure run
     * (D12).
     */
    @Test
    void aRefusalIsRecordedWithoutCountingTowardTheFailureRun() {
        failTimes(2);
        audit.reset();
        UUID ada = users.require("ada").id();

        attempts.recordRefusal(ada, AuditRefusalReason.ACCOUNT_LOCKED, AuditLoginMethod.SSO);

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(ada, AuditRefusalReason.ACCOUNT_LOCKED.name()));
        assertThat(audit.loginMethods()).containsExactly(AuditLoginMethod.SSO);
    }

    /** An Epic success without a factor is a caller's bug, refused before anything is written. */
    @Test
    void anEpicSuccessWithoutAFactorIsRefused() {
        failTimes(2);

        assertThatNullPointerException()
                .isThrownBy(() -> attempts.recordEpicSuccess("ada", null, null));
        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS)).isEmpty();
    }

    /**
     * Recording nothing for a name that does not exist is what keeps a refusal
     * uninformative: no resource appears, so stored state cannot be used to enumerate
     * identities.
     */
    @Test
    void aRefusalForAnUnknownUsernameIsNotRecordedAnywhere() {
        attempts.recordFailure("nobody", AuditRefusalReason.BAD_CREDENTIALS);

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("nobody"))).isEmpty();
        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
    }

    @Test
    void anAcceptedLoginForAnUnknownUsernameIsANoOp() {
        attempts.recordPasswordSuccess("nobody", null);

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("nobody"))).isEmpty();
        assertThat(transaction.pending()).as("nothing to revoke for nobody").isZero();
    }

    // ---- one concurrent session per User ----------------------------------------------------

    /**
     * A blank name reaches here only if the web adapter's validation was bypassed; it is nobody,
     * exactly as an unknown name is — a subject-less failure, and a success that touches no one.
     */
    @Test
    void aBlankUsernameIsTreatedAsNobody() {
        attempts.recordFailure("   ", AuditRefusalReason.BAD_CREDENTIALS);
        attempts.recordPasswordSuccess("   ", null);
        attempts.recordFailure(null, AuditRefusalReason.BAD_CREDENTIALS);

        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId)
                .containsExactly(null, null);
        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS)).isEmpty();
        assertThat(transaction.pending()).isZero();
    }

    /**
     * An accepted login ends every other session the User holds and keeps the one it is completed
     * in, and nobody else's session moves.
     */
    @Test
    void anAcceptedLoginEndsTheUsersOtherSessionsAndKeepsTheRetainedOne() {
        users.given(ScimIdentities.user("bob"));
        java.util.UUID ada = users.require("ada").id();
        java.util.UUID bob = users.require("bob").id();
        sessions.open(ada, "ada-earlier");
        sessions.open(ada, "ada-current");
        sessions.open(bob, "bob-only");

        attempts.recordPasswordSuccess("ada", "ada-current");
        transaction.commit();

        assertThat(sessions.sessionsOf(ada)).containsExactly("ada-current");
        assertThat(sessions.sessionsOf(bob)).containsExactly("bob-only");
        assertThat(sessions.loginRevocations()).containsExactly(ada);
    }

    /** A caller holding no session keeps none, so every earlier session of the User ends. */
    @Test
    void anAcceptedLoginWithoutASessionEndsEverySessionOfTheUser() {
        java.util.UUID ada = users.require("ada").id();
        sessions.open(ada, "ada-earlier");

        attempts.recordPasswordSuccess("ada", null);
        transaction.commit();

        assertThat(sessions.sessionsOf(ada)).isEmpty();
    }

    /**
     * The revocation waits for the commit: before it nothing has ended, and a login whose
     * transaction rolls back has signed its owner out nowhere.
     */
    @Test
    void theOtherSessionsEndOnlyOnceTheLoginCommits() {
        java.util.UUID ada = users.require("ada").id();
        sessions.open(ada, "ada-earlier");

        attempts.recordPasswordSuccess("ada", "ada-current");
        assertThat(sessions.sessionsOf(ada)).as("before the commit").containsExactly("ada-earlier");

        transaction.rollback();
        assertThat(sessions.sessionsOf(ada)).as("after a rollback").containsExactly("ada-earlier");
        assertThat(sessions.loginRevocations()).isEmpty();
    }

    /** A login confined by a required change follows the same rule as any other. */
    @Test
    void aConfinedLoginAlsoEndsTheUsersOtherSessions() {
        Instant earlier = NOW.minus(Duration.ofDays(10));
        users.given(ScimIdentities.userWithLoginState(
                "bob", new ScimLoginState("hash", 0, null, earlier, earlier)));
        java.util.UUID bob = users.require("bob").id();
        sessions.open(bob, "bob-earlier");
        sessions.open(bob, "bob-current");

        attempts.recordPasswordSuccess("bob", "bob-current");
        transaction.commit();

        assertThat(sessions.sessionsOf(bob)).containsExactly("bob-current");
    }

    /**
     * An identity with no failure run has nothing to clear, so the login must not rewrite
     * its failure run — every accepted login would otherwise cost a pointless update. (The
     * dormancy basis is still recorded, through its own narrow write, which the fake's write
     * count does not include; see the tests below.)
     */
    @Test
    void anAcceptedLoginOnAnUntouchedIdentityRewritesNoFailureRun() {
        int writesBefore = users.writes();

        attempts.recordPasswordSuccess("ada", null);

        assertThat(users.writes()).isEqualTo(writesBefore);
    }

    /**
     * Every accepted login records when it happened — the basis the dormancy job measures
     * dormancy from — and a later login moves it forward.
     */
    @Test
    void everyAcceptedLoginRecordsWhenItHappened() {
        assertThat(users.require("ada").login().lastAuthenticatedAt()).isNull();

        attempts.recordPasswordSuccess("ada", null);
        assertThat(users.require("ada").login().lastAuthenticatedAt()).isEqualTo(NOW);

        clock.advanceBy(Duration.ofDays(3));
        attempts.recordPasswordSuccess("ada", null);
        assertThat(users.require("ada").login().lastAuthenticatedAt())
                .isEqualTo(NOW.plus(Duration.ofDays(3)));
    }

    /**
     * Recording a login moves nothing a connector reads: the dormancy basis is not a SCIM
     * attribute, so neither the version nor {@code lastModified} advances.
     */
    @Test
    void recordingALoginDoesNotAdvanceTheVersion() {
        ScimUser before = users.require("ada");
        clock.advanceBy(Duration.ofHours(1));

        attempts.recordPasswordSuccess("ada", null);

        ScimUser after = users.require("ada");
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.lastModifiedAt()).isEqualTo(before.lastModifiedAt());
    }

    /**
     * A login by a User that still owes a required password change is confined to the change and
     * logout, so it is not use of the account: it leaves the dormancy basis where it was, or an
     * imposed credential nobody replaces would never age into deactivation. It is still an
     * accepted login — the failure run clears and the success is audited.
     */
    @Test
    void aConfinedLoginDoesNotMoveTheDormancyBasis() {
        Instant earlier = NOW.minus(Duration.ofDays(10));
        users.given(ScimIdentities.userWithLoginState(
                "bob", new ScimLoginState("hash", 2, null, earlier, earlier)));

        attempts.recordPasswordSuccess("bob", null);

        ScimUser bob = users.require("bob");
        assertThat(bob.login().lastAuthenticatedAt()).isEqualTo(earlier);
        assertThat(bob.login().failedLoginAttempts()).isZero();
        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(Recorded::subjectId)
                .containsExactly(bob.id());
    }

    /** A refused attempt is not an authentication, so it leaves the dormancy basis alone. */
    @Test
    void aRefusedAttemptDoesNotRecordAnAuthentication() {
        attempts.recordPasswordSuccess("ada", null);
        clock.advanceBy(Duration.ofDays(1));

        attempts.recordFailure("ada", AuditRefusalReason.BAD_CREDENTIALS);

        assertThat(users.require("ada").login().lastAuthenticatedAt()).isEqualTo(NOW);
    }

    @Test
    void anAcceptedLoginAfterAFailureDoesWrite() {
        attempts.recordFailure("ada", AuditRefusalReason.BAD_CREDENTIALS);
        int writesBefore = users.writes();

        attempts.recordPasswordSuccess("ada", null);

        assertThat(users.writes()).isEqualTo(writesBefore + 1);
    }

    // What a counted failure implies — the Lockout, its audit, the revocation, the Bootstrap
    // Admin's exemption — is FailureCounter's and is tested in FailureCounterTests. What stays
    // here is the login path's own: that it counts, the refusal it records, and where that
    // refusal lands relative to the Lockout's events.

    /**
     * The login path counts into the shared run: its third refusal imposes the Lockout, and its
     * own refusal is recorded after the Lockout's event, never before it.
     */
    @Test
    void theRefusalThatImposesTheLockoutIsRecordedAfterIt() {
        failTimes(2);
        audit.reset();

        attempts.recordFailure("ada", AuditRefusalReason.BAD_CREDENTIALS);

        java.util.UUID ada = users.require("ada").id();
        assertThat(users.require("ada").login().isLocked()).isTrue();
        assertThat(audit.recorded()).containsExactly(
                new Recorded(AuditOperation.LOCKOUT_SET, null, ada, null),
                new Recorded(AuditOperation.LOGIN_FAILURE, null, ada,
                        AuditRefusalReason.BAD_CREDENTIALS.name()));
        assertThat(transaction.pending()).as("the Lockout's revocation").isEqualTo(1);
    }

    // The Bootstrap Admin, whose refusals are audited like anyone's

    @Test
    void everyBootstrapAdminFailureIsAuditedAgainstItsStableId() {
        ScimUser recovery = givenBootstrapAdmin("recovery-admin");

        failTimes(10, "recovery-admin");

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE)).hasSize(10)
                .allSatisfy(event -> assertThat(event.subjectId()).isEqualTo(recovery.id()));
        assertThat(users.require("recovery-admin").login().failedLoginAttempts())
                .as("counted on this path too").isEqualTo(10);
    }

    // What the trail is told, which is the other half of counting an attempt

    @Test
    void aRefusedAttemptIsRecordedAgainstTheIdentitysStableIdWithItsReason() {
        attempts.recordFailure("ada", AuditRefusalReason.BAD_CREDENTIALS);

        assertThat(audit.recorded()).containsExactly(new Recorded(
                AuditOperation.LOGIN_FAILURE,
                null,
                users.require("ada").id(),
                AuditRefusalReason.BAD_CREDENTIALS.name()));
    }

    /**
     * No identity carries the name, so there is no subject — and the submitted value is
     * not recorded in its place. The reason the caller supplied is replaced too: whether
     * the name exists is settled here, by looking, not guessed from an exception type the
     * authentication library deliberately makes ambiguous.
     */
    @Test
    void aRefusalForAnUnknownUsernameIsRecordedWithNoSubjectAndItsOwnReason() {
        attempts.recordFailure("nobody", AuditRefusalReason.BAD_CREDENTIALS);

        assertThat(audit.recorded()).containsExactly(new Recorded(
                AuditOperation.LOGIN_FAILURE,
                null,
                null,
                AuditRefusalReason.UNKNOWN_ACCOUNT.name()));
    }

    /** A refusal of an identity already locked is still the login path's to record. */
    @Test
    void aRefusalOfALockedIdentityIsStillRecordedWithItsReason() {
        failTimes(3);
        audit.reset();

        attempts.recordFailure("ada", AuditRefusalReason.ACCOUNT_LOCKED);

        assertThat(audit.recorded()).containsExactly(new Recorded(
                AuditOperation.LOGIN_FAILURE,
                null,
                users.require("ada").id(),
                AuditRefusalReason.ACCOUNT_LOCKED.name()));
    }

    /**
     * Nothing on this path can record a lift. There is no unrequested lift to record: the
     * only one is an administrator's Unlock, which happens in
     * {@link IdentityAdministrationService} and names its actor.
     */
    @Test
    void noLoginAttemptEverRecordsALockoutLift() {
        failTimes(3);
        clock.advanceBy(A_LONG_TIME);

        attempts.recordFailure("ada", AuditRefusalReason.ACCOUNT_LOCKED);
        attempts.recordPasswordSuccess("ada", null);

        assertThat(audit.of(AuditOperation.LOCKOUT_LIFT)).isEmpty();
    }

    @Test
    void anAcceptedLoginIsRecordedAgainstTheIdentitysStableId() {
        attempts.recordPasswordSuccess("ada", null);

        assertThat(audit.recorded()).containsExactly(new Recorded(
                AuditOperation.LOGIN_SUCCESS,
                users.require("ada").id(),
                users.require("ada").id(),
                null));
    }

    @Test
    void anAcceptedLoginForAnUnknownUsernameRecordsNothing() {
        attempts.recordPasswordSuccess("nobody", null);

        assertThat(audit.recorded()).isEmpty();
    }

    // ---- a wrong current password on the self-service change -------------------------------

    /**
     * A wrong current password counts toward the same run as a refused login, so it locks at the
     * same threshold. It is audited as a refused change rather than a login failure, recorded
     * after the Lockout's event on the attempt that imposes it.
     */
    @Test
    void aWrongCurrentPasswordCountsTowardTheRunAndIsRefusedAfterTheLockoutItImposes() {
        java.util.UUID ada = users.require("ada").id();

        attempts.recordPasswordChangeFailure(ada);
        attempts.recordPasswordChangeFailure(ada);
        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
        assertThat(audit.recorded())
                .extracting(Recorded::operation, Recorded::subjectId, Recorded::detail)
                .containsOnly(tuple(
                        AuditOperation.PASSWORD_CHANGE, ada, "BAD_CURRENT_PASSWORD"))
                .hasSize(2);
        audit.reset();

        attempts.recordPasswordChangeFailure(ada);

        assertThat(users.require("ada").login().isLocked()).isTrue();
        assertThat(audit.recorded())
                .extracting(Recorded::operation, Recorded::detail)
                .containsExactly(
                        tuple(AuditOperation.LOCKOUT_SET, null),
                        tuple(
                                AuditOperation.PASSWORD_CHANGE, "BAD_CURRENT_PASSWORD"));
        assertThat(audit.of(AuditOperation.LOGIN_FAILURE)).isEmpty();
        assertThat(transaction.pending()).as("the Lockout's revocation").isEqualTo(1);
    }

    /** An id naming nobody is ignored: nothing counted, nothing audited. */
    @Test
    void aWrongCurrentPasswordForAnUnknownIdRecordsNothing() {
        attempts.recordPasswordChangeFailure(java.util.UUID.randomUUID());

        assertThat(audit.recorded()).isEmpty();
        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
    }

    /**
     * The reservation is applied through the port, because production has no other way to
     * produce one: there is deliberately no factory that mints a reserved resource.
     */
    private ScimUser givenBootstrapAdmin(String userName) {
        return users.createReserved(
                ScimIdentities.user(userName), ReservedResourceName.BOOTSTRAP_ADMIN);
    }

    private void failTimes(int times) {
        failTimes(times, "ada");
    }

    private void failTimes(int times, String userName) {
        for (int attempt = 0; attempt < times; attempt++) {
            attempts.recordFailure(userName, AuditRefusalReason.BAD_CREDENTIALS);
        }
    }
}
