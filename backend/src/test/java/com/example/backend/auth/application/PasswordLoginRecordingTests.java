package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.LoginHarness;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

/**
 * What a password Login records, at the one interface it has ({@link LoginService#logIn}): the
 * failure it counts, the {@code LOGIN_FAILURE} or {@code LOGIN_SUCCESS} it writes, the sweep of the
 * User's other sessions, and the one transaction — and one lock — a refusal is decided and counted
 * in. Over the real chain {@link LoginHarness} assembles, at a threshold of three.
 *
 * <p>The failure run and the lock instant live in {@code ScimLoginState} and are written through
 * the port's narrow login-state operation, so every assertion about them is read back off
 * {@code user.login()}. What a counted failure implies — the Lockout, its audit, the revocation,
 * the Bootstrap Admin's exemption — is {@link LoginStateService}'s, and is tested in
 * {@code LoginStateServiceTests}; what stays here is the Login's own: that it counts, the record
 * it writes, and where that record lands relative to the Lockout's events.
 */
class PasswordLoginRecordingTests {

    private static final Instant NOW = LoginHarness.NOW;

    private static final String PASSWORD = "correct-password";

    private static final String WRONG_PASSWORD = "not-the-password";

    private final MutableClock clock = new MutableClock(NOW);
    private final LoginHarness harness =
            LoginHarness.builder().clock(clock).lockoutThreshold(3).build();
    private final InMemoryScimUserRepository users = harness.users();
    private final InMemoryAccountSessions sessions = harness.sessions();
    private final PendingCommit transaction = harness.transaction();
    private final RecordingAuditTrail audit = harness.audit();
    private final LoginService login = harness.login();

    private final String hash = harness.passwordEncoder().encode(PASSWORD);

    private final UUID ada = users.given(withPassword("ada", new ScimLoginState(hash, 0, null))).id();

    // ---- a refusal decided under the lock, counted under it ----------------------------------

    /**
     * A password replaced while the old one was being compared refuses the Login under the
     * User's lock, and that refusal is counted under the same lock: the User's lock is asked for
     * once, not taken a second time to count what the first decided.
     */
    @Test
    void aRefusalDecidedUnderTheLockIsCountedWithoutTakingTheLockAgain() {
        users.afterNextNameLookup(this::replaceAdasPassword);

        login.logIn("ada", PASSWORD, null);

        assertThat(harness.locks().requests()).containsExactly(Set.of(ada));
    }

    /** ... and in the one transaction the decision was taken in, which commits both together. */
    @Test
    void aRefusalDecidedUnderTheLockIsDecidedAndCountedInOneTransaction() {
        users.afterNextNameLookup(this::replaceAdasPassword);

        login.logIn("ada", PASSWORD, null);

        assertThat(List.of(harness.transactions().begun(), harness.transactions().committed(),
                users.require("ada").login().failedLoginAttempts()))
                .containsExactly(1, 1, 1);
    }

    /** A refusal by the authentication is counted and recorded in one transaction too. */
    @Test
    void aRefusalByTheAuthenticationIsCountedAndRecordedInOneTransaction() {
        fail("ada");

        assertThat(List.of(harness.transactions().begun(), harness.transactions().committed(),
                audit.of(AuditOperation.LOGIN_FAILURE).size()))
                .containsExactly(1, 1, 1);
    }

    /** An accepted Login is decided and recorded in one transaction, which commits. */
    @Test
    void anAcceptedLoginIsDecidedAndRecordedInOneTransaction() {
        succeed("ada", null);

        assertThat(List.of(harness.transactions().begun(), harness.transactions().committed(),
                audit.of(AuditOperation.LOGIN_SUCCESS).size()))
                .containsExactly(1, 1, 1);
    }

    // ---- a fault during the lookup Spring wraps as InternalAuthenticationServiceException -----

    /**
     * {@code DaoAuthenticationProvider.retrieveUser} wraps any exception the
     * {@code UserDetailsService} throws that is not a {@code UsernameNotFoundException} — a
     * {@code DataAccessException} from a query timeout or a pool-acquire timeout, say — in an
     * {@code InternalAuthenticationServiceException}, and {@code ProviderManager} rethrows it.
     * That is a server fault, not a refusal: nothing is counted, no {@code LOGIN_FAILURE}
     * is audited, and the cause reaches the caller as a {@link RuntimeException} of its own type —
     * never wrapped — so the application's fault handling classifies and redacts it exactly as
     * it would any other database fault, rather than as an opaque
     * {@code InternalAuthenticationServiceException} whose message Spring copied the cause's
     * into, which may carry SQL detail.
     */
    @Test
    void aLookupFaultWrappedByInternalAuthenticationServiceExceptionIsRethrownAsItsCause() {
        QueryTimeoutException lookupFault = new QueryTimeoutException(
                "SELECT * FROM scim_users", new SQLException("connection timed out"));
        LoginHarness faulting = LoginHarness.builder()
                .clock(clock)
                .lockoutThreshold(3)
                .authentication(real -> request -> {
                    throw new InternalAuthenticationServiceException(
                            lookupFault.getMessage(), lookupFault);
                })
                .build();

        assertThatThrownBy(() -> faulting.login().logIn("ada", PASSWORD, null))
                .isSameAs(lookupFault);
    }

    /** Nothing is counted, audited, or transacted: the fault is answered, never refused. */
    @Test
    void aLookupFaultWrappedByInternalAuthenticationServiceExceptionCountsAndAuditsNothing() {
        QueryTimeoutException lookupFault = new QueryTimeoutException(
                "SELECT * FROM scim_users", new SQLException("connection timed out"));
        LoginHarness faulting = LoginHarness.builder()
                .clock(clock)
                .lockoutThreshold(3)
                .authentication(real -> request -> {
                    throw new InternalAuthenticationServiceException(
                            lookupFault.getMessage(), lookupFault);
                })
                .build();

        assertThatThrownBy(() -> faulting.login().logIn("ada", PASSWORD, null));

        assertThat(faulting.audit().recorded()).isEmpty();
        assertThat(faulting.transactions().begun()).isZero();
    }

    /**
     * Unwrapped only when the cause is a {@link RuntimeException}: an
     * {@code InternalAuthenticationServiceException} whose cause is not one — Spring's own
     * constructor permits any {@link Throwable} — is rethrown as itself, so the fault handling
     * still receives an exception rather than nothing.
     */
    @Test
    void aLookupFaultWithNoRuntimeExceptionCauseIsRethrownAsTheWrapper() {
        InternalAuthenticationServiceException noRuntimeCause =
                new InternalAuthenticationServiceException("wrapped");

        assertThatThrownBy(() -> {
            LoginHarness faulting = LoginHarness.builder()
                    .clock(clock)
                    .lockoutThreshold(3)
                    .authentication(real -> request -> {
                        throw noRuntimeCause;
                    })
                    .build();
            faulting.login().logIn("ada", PASSWORD, null);
        }).isSameAs(noRuntimeCause);
    }

    // ---- counting a refusal ------------------------------------------------------------------

    @Test
    void aRefusedAttemptIsCountedAgainstTheIdentity() {
        fail("ada");

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(1);
        assertThat(users.require("ada").login().isLocked()).isFalse();
    }

    /** The reason is the audit trail's: it names the User, and counts toward its failure run. */
    @Test
    void aPasswordRefusalIsAuditedWithItsReasonAndSubjectAndCountsTowardTheFailureRun() {
        fail("ada");

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.LOGIN_FAILURE, null, ada, "BAD_CREDENTIALS"));
        assertThat(audit.loginMethods()).containsExactly(AuditLoginMethod.PASSWORD);
        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void anAcceptedLoginResetsTheFailureCount() {
        failTimes(2);

        succeed("ada", null);

        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
        assertThat(users.require("ada").login().lockedAt()).isNull();
    }

    /** A password Login's {@code LOGIN_SUCCESS} names method {@code password} and no factor. */
    @Test
    void aPasswordSuccessIsRecordedUnderPasswordWithNoFactor() {
        succeed("ada", null);

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(Recorded::subjectId)
                .containsExactly(ada);
        assertThat(audit.loginMethods()).containsExactly(AuditLoginMethod.PASSWORD);
        assertThat(audit.mfaFactors()).containsExactly((AuditMfaFactor) null);
    }

    /**
     * Recording nothing for a name that does not exist is what keeps a refusal uninformative: no
     * resource appears, so stored state cannot be used to enumerate identities.
     */
    @Test
    void aRefusalForAnUnknownUsernameIsNotRecordedAnywhere() {
        fail("nobody");

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("nobody"))).isEmpty();
        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
    }

    /** The reason a refusal was recorded under: the reported one, or unknown when nobody was resolved. */
    @Test
    void aRefusalReportsTheReasonItWasRecordedUnder() {
        assertThat(List.of(refusalOf(fail("ada")).recorded(), refusalOf(fail("nobody")).recorded()))
                .containsExactly(
                        PasswordRefusalReason.BAD_CREDENTIALS, PasswordRefusalReason.UNKNOWN_ACCOUNT);
    }

    /**
     * A refusal whose authentication resolved no User is recorded, and counted, as the unknown
     * account it is.
     */
    @Test
    void aPasswordRefusalOfAnUnknownNameIsAuditedAndCountedAsAnUnknownAccount() {
        fail("nobody");

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.LOGIN_FAILURE, null, null, "UNKNOWN_ACCOUNT"));
        assertThat(harness.counts().moved()).containsExactly("password:refused:UNKNOWN_ACCOUNT");
    }

    /**
     * A User the authentication resolved but gone by the time its refusal is recorded is nobody,
     * exactly as a name that resolved no User is — a subject-less failure that touches no one.
     */
    @Test
    void aRefusalOfAUserGoneByThenIsRecordedAgainstNobody() {
        users.afterNextNameLookup(() -> users.deleteById(ada, NOW));

        fail("ada");

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(null, AuditRefusalReason.UNKNOWN_ACCOUNT.name()));
    }

    /** A refused attempt is not an authentication, so it leaves the dormancy basis alone. */
    @Test
    void aRefusedAttemptDoesNotRecordAnAuthentication() {
        succeed("ada", null);
        clock.advanceBy(Duration.ofDays(1));

        fail("ada");

        assertThat(users.require("ada").login().lastAuthenticatedAt()).isEqualTo(NOW);
    }

    // ---- an accepted Login for a User gone before its lock -----------------------------------

    /**
     * A success names the User by the stable id its authentication carries. One whose User is
     * gone by then — deleted while the password was being compared — is refused as a User gone
     * under its lock is: not accepted, and not a fault.
     */
    @Test
    void anAcceptedLoginForAUserNoLongerThereIsRefusedAsGoneUnderTheLock() {
        users.afterNextNameLookup(() -> users.deleteById(ada, NOW));

        assertThat(refusalOf(succeed("ada", null)).reason())
                .isEqualTo(LoginStateService.StandingRefusal.DEACTIVATED.passwordReason());
    }

    @Test
    void anAcceptedLoginForAUserNoLongerThereSchedulesNoRevocation() {
        users.afterNextNameLookup(() -> users.deleteById(ada, NOW));

        succeed("ada", null);

        assertThat(transaction.pending()).as("nothing to revoke for nobody").isZero();
    }

    /** Refused with nothing written: no cleared run, no dormancy basis, for nobody. */
    @Test
    void anAcceptedLoginForAUserNoLongerThereWritesNothing() {
        int[] before = new int[1];
        users.afterNextNameLookup(() -> {
            users.deleteById(ada, NOW);
            before[0] = users.writes();
        });

        succeed("ada", null);

        assertThat(users.writes()).isEqualTo(before[0]);
    }

    /** It records no success: its refusal is the one {@code LOGIN_FAILURE}, against nobody. */
    @Test
    void anAcceptedLoginForAUserNoLongerThereRecordsNothing() {
        users.afterNextNameLookup(() -> users.deleteById(ada, NOW));

        succeed("ada", null);

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS)).isEmpty();
    }

    // ---- a success refused on the standing found under the lock ------------------------------

    /**
     * The authentication read the User unlocked, and a Lockout landed while the password was being
     * compared: the success is refused, so no {@code LOGIN_SUCCESS} is written for it.
     */
    @Test
    void aSuccessForAUserLockedByThenRecordsNoLoginSuccess() {
        lockAdaOnceTheComparisonIsDone();

        succeed("ada", null);

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS)).isEmpty();
    }

    @Test
    void aSuccessForAUserLockedByThenEndsNoOtherSession() {
        sessions.open(ada, "ada-current");
        sessions.open(ada, "ada-elsewhere");
        lockAdaOnceTheComparisonIsDone();

        succeed("ada", "ada-current");
        transaction.commit();

        assertThat(sessions.loginRevocations()).isEmpty();
    }

    @Test
    void aSuccessForAUserLockedByThenIsReportedAsRefusedForTheLock() {
        lockAdaOnceTheComparisonIsDone();

        assertThat(refusalOf(succeed("ada", null)).reason())
                .isEqualTo(LoginStateService.StandingRefusal.LOCKED.passwordReason());
    }

    @Test
    void anAcceptedSuccessIsReportedAsRefusedForNothing() {
        assertThat(succeed("ada", null).accepted()).isPresent();
    }

    // ---- one concurrent session per User ----------------------------------------------------

    /**
     * An accepted login ends every other session the User holds and keeps the one it is completed
     * in, and nobody else's session moves.
     */
    @Test
    void anAcceptedLoginEndsTheUsersOtherSessionsAndKeepsTheRetainedOne() {
        UUID bob = users.given(withPassword("bob", new ScimLoginState(hash, 0, null))).id();
        sessions.open(ada, "ada-earlier");
        sessions.open(ada, "ada-current");
        sessions.open(bob, "bob-only");

        succeed("ada", "ada-current");
        transaction.commit();

        assertThat(sessions.sessionsOf(ada)).containsExactly("ada-current");
        assertThat(sessions.sessionsOf(bob)).containsExactly("bob-only");
        assertThat(sessions.loginRevocations()).containsExactly(ada);
    }

    /** A Login that ended another session is audited under one session per User's cause. */
    @Test
    void anAcceptedLoginThatEndedAnotherSessionAuditsItAsReplacedByLogin() {
        sessions.open(ada, "ada-earlier");
        sessions.open(ada, "ada-current");

        succeed("ada", "ada-current");
        transaction.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE)).containsExactly(new Recorded(
                AuditOperation.USER_SESSIONS_REVOKE, null, ada, "SUCCESS::REPLACED_BY_LOGIN"));
    }

    /** A Login that kept the User's only session ended nothing, so nothing is revoked on record. */
    @Test
    void anAcceptedLoginThatEndedNoOtherSessionAuditsNoRevocation() {
        sessions.open(ada, "ada-current");

        succeed("ada", "ada-current");
        transaction.commit();

        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE)).isEmpty();
    }

    /** A caller holding no session keeps none, so every earlier session of the User ends. */
    @Test
    void anAcceptedLoginWithoutASessionEndsEverySessionOfTheUser() {
        sessions.open(ada, "ada-earlier");

        succeed("ada", null);
        transaction.commit();

        assertThat(sessions.sessionsOf(ada)).isEmpty();
    }

    /**
     * The revocation waits for the commit: before it nothing has ended, and a login whose
     * transaction rolls back has signed its owner out nowhere.
     */
    @Test
    void theOtherSessionsEndOnlyOnceTheLoginCommits() {
        sessions.open(ada, "ada-earlier");

        succeed("ada", "ada-current");
        assertThat(sessions.sessionsOf(ada)).as("before the commit").containsExactly("ada-earlier");

        transaction.rollback();
        assertThat(sessions.sessionsOf(ada)).as("after a rollback").containsExactly("ada-earlier");
        assertThat(sessions.loginRevocations()).isEmpty();
    }

    /** A login confined by a required change follows the same rule as any other. */
    @Test
    void aConfinedLoginAlsoEndsTheUsersOtherSessions() {
        Instant earlier = NOW.minus(Duration.ofDays(10));
        UUID bob = users.given(withPassword(
                "bob", new ScimLoginState(hash, 0, null, earlier, earlier))).id();
        sessions.open(bob, "bob-earlier");
        sessions.open(bob, "bob-current");

        succeed("bob", "bob-current");
        transaction.commit();

        assertThat(sessions.sessionsOf(bob)).containsExactly("bob-current");
    }

    // ---- what an accepted Login writes -------------------------------------------------------

    /**
     * An identity with no failure run has nothing to clear, so the login must not rewrite
     * its failure run — every accepted login would otherwise cost a pointless update. (The
     * dormancy basis is still recorded, through its own narrow write, which the fake's write
     * count does not include; see the tests below.)
     */
    @Test
    void anAcceptedLoginOnAnUntouchedIdentityRewritesNoFailureRun() {
        int writesBefore = users.writes();

        succeed("ada", null);

        assertThat(users.writes()).isEqualTo(writesBefore);
    }

    /**
     * Every accepted login records when it happened — the basis the dormancy job measures
     * dormancy from — and a later login moves it forward.
     */
    @Test
    void everyAcceptedLoginRecordsWhenItHappened() {
        assertThat(users.require("ada").login().lastAuthenticatedAt()).isNull();

        succeed("ada", null);
        assertThat(users.require("ada").login().lastAuthenticatedAt()).isEqualTo(NOW);

        clock.advanceBy(Duration.ofDays(3));
        succeed("ada", null);
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

        succeed("ada", null);

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
        UUID bob = users.given(withPassword(
                "bob", new ScimLoginState(hash, 2, null, earlier, earlier))).id();

        succeed("bob", null);

        assertThat(users.require("bob").login().lastAuthenticatedAt()).isEqualTo(earlier);
        assertThat(users.require("bob").login().failedLoginAttempts()).isZero();
        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(Recorded::subjectId)
                .containsExactly(bob);
    }

    @Test
    void anAcceptedLoginAfterAFailureDoesWrite() {
        fail("ada");
        int writesBefore = users.writes();

        succeed("ada", null);

        assertThat(users.writes()).isEqualTo(writesBefore + 1);
    }

    @Test
    void anAcceptedLoginIsRecordedAgainstTheIdentitysStableId() {
        succeed("ada", null);

        assertThat(audit.recorded()).containsExactly(new Recorded(
                AuditOperation.LOGIN_SUCCESS, ada, ada, null));
    }

    // ---- where a refusal lands beside the Lockout it imposes ---------------------------------

    /**
     * The login path counts into the shared run: its third refusal imposes the Lockout, and its
     * own refusal is recorded after the Lockout's event, never before it.
     */
    @Test
    void theRefusalThatImposesTheLockoutIsRecordedAfterIt() {
        failTimes(2);
        audit.reset();

        fail("ada");

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
        fail("ada");

        assertThat(audit.recorded()).containsExactly(new Recorded(
                AuditOperation.LOGIN_FAILURE, null, ada, AuditRefusalReason.BAD_CREDENTIALS.name()));
    }

    /**
     * The authentication resolved no identity, so there is no subject — and the submitted value
     * is not recorded in its place. The reason reported is replaced too: whether the name named a
     * User is settled by what the authentication's read found, not guessed from an exception type
     * the authentication library deliberately makes ambiguous.
     */
    @Test
    void aRefusalForAnUnknownUsernameIsRecordedWithNoSubjectAndItsOwnReason() {
        fail("nobody");

        assertThat(audit.recorded()).containsExactly(new Recorded(
                AuditOperation.LOGIN_FAILURE, null, null, AuditRefusalReason.UNKNOWN_ACCOUNT.name()));
    }

    /** A refusal of an identity already locked is still the login path's to record. */
    @Test
    void aRefusalOfALockedIdentityIsStillRecordedWithItsReason() {
        failTimes(3);
        audit.reset();

        succeed("ada", null);

        assertThat(audit.recorded()).containsExactly(new Recorded(
                AuditOperation.LOGIN_FAILURE, null, ada, AuditRefusalReason.ACCOUNT_LOCKED.name()));
    }

    // ---- the paths each refusal names: exactly what counting it moved -------------------------

    /** A wrong password for a User that is not locked lengthens its run, and says so. */
    @Test
    void aRefusedLoginThatLengthenedTheRunNamesIt() {
        fail("ada");

        assertThat(audit.changedPaths(AuditOperation.LOGIN_FAILURE))
                .containsExactly(List.of("failedLoginAttempts"));
    }

    /** The Bootstrap Admin's run counts and never locks, so each refusal names the run alone. */
    @Test
    void aBootstrapAdminsRefusedLoginNamesTheRunEvenPastTheLimit() {
        givenBootstrapAdmin("recovery-admin");
        failTimes(3, "recovery-admin");
        audit.reset();

        fail("recovery-admin");

        assertThat(audit.changedPaths(AuditOperation.LOGIN_FAILURE))
                .containsExactly(List.of("failedLoginAttempts"));
    }

    /**
     * The refusal that imposes the Lockout moved the run and the lock, and both its rows name
     * them: the {@code LOCKOUT_SET}, and the {@code LOGIN_FAILURE} after it.
     */
    @Test
    void theRefusalThatImposesTheLockoutNamesTheRunAndTheLockOnBothRows() {
        failTimes(2);
        audit.reset();

        fail("ada");

        List<String> runAndLock = List.of("failedLoginAttempts", "lockedAt", "lockCause");
        assertThat(List.of(audit.changedPaths(AuditOperation.LOCKOUT_SET),
                audit.changedPaths(AuditOperation.LOGIN_FAILURE)))
                .containsExactly(List.of(runAndLock), List.of(runAndLock));
    }

    /**
     * A refusal decided under the lock moves what counting it moved, and its record names that:
     * a password replaced during the comparison lengthens the run as a wrong one does.
     */
    @Test
    void aRefusalDecidedUnderTheLockNamesTheRunItLengthened() {
        users.afterNextNameLookup(this::replaceAdasPassword);

        login.logIn("ada", PASSWORD, null);

        assertThat(audit.changedPaths(AuditOperation.LOGIN_FAILURE))
                .containsExactly(List.of("failedLoginAttempts"));
    }

    /** A locked User's attempts neither count nor deepen the lock, whatever its cause. */
    @ParameterizedTest(name = "locked for {0}")
    @EnumSource(LockCause.class)
    void aRefusedLoginOfALockedUserNamesNoPath(LockCause cause) {
        ScimIdentities.storeFailureRun(users, ada, new ScimLoginState(null, 1, NOW, cause, null, null));

        succeed("ada", null);

        assertThat(audit.changedPaths(AuditOperation.LOGIN_FAILURE)).containsExactly(List.of());
    }

    /** An unknown name has no User to count against, so nothing moved. */
    @Test
    void aRefusedLoginUnderAnUnknownNameNamesNoPath() {
        fail("nobody");

        assertThat(audit.changedPaths(AuditOperation.LOGIN_FAILURE)).containsExactly(List.of());
    }

    /**
     * Nothing on this path can record a lift. There is no unrequested lift to record: the
     * only one is an administrator's Unlock, which happens in
     * {@link IdentityAdministrationService} and names its actor.
     */
    @Test
    void noLoginAttemptEverRecordsALockoutLift() {
        failTimes(3);

        succeed("ada", null);

        assertThat(audit.of(AuditOperation.LOCKOUT_LIFT)).isEmpty();
    }

    /**
     * The reservation is applied through the port, because production has no other way to
     * produce one: there is deliberately no factory that mints a reserved resource.
     */
    private ScimUser givenBootstrapAdmin(String userName) {
        return users.createReserved(withPassword(userName, new ScimLoginState(hash, 0, null)),
                ReservedResourceName.BOOTSTRAP_ADMIN);
    }

    private ScimUser withPassword(String userName, ScimLoginState login) {
        return new ScimUser(UUID.randomUUID(), ScimIdentities.profile(userName, true), login, null,
                ScimUser.INITIAL_VERSION, ScimIdentities.NOW, ScimIdentities.NOW);
    }

    /** Ada's password, replaced by a connector the moment her Login's one read has found her. */
    private void replaceAdasPassword() {
        users.given(new ScimUser(ada, ScimIdentities.profile("ada", true),
                new ScimLoginState(harness.passwordEncoder().encode("connector-set-password"), 0,
                        null),
                null, ScimUser.INITIAL_VERSION, ScimIdentities.NOW, ScimIdentities.NOW));
    }

    /** Another attempt's Lockout of ada, committed just before her Login takes her lock. */
    private void lockAdaOnceTheComparisonIsDone() {
        harness.locks().beforeNextGrant(() -> ScimIdentities.storeFailureRun(users, ada,
                new ScimLoginState(null, 3, NOW, LockCause.FAILURES, null, null)));
    }

    private LoginDecision succeed(String userName, String retainedSessionId) {
        return login.logIn(userName, PASSWORD, retainedSessionId);
    }

    private LoginDecision fail(String userName) {
        return login.logIn(userName, WRONG_PASSWORD, null);
    }

    private static PasswordRefused refusalOf(LoginDecision decision) {
        return (PasswordRefused) decision.outcome();
    }

    private void failTimes(int times) {
        failTimes(times, "ada");
    }

    private void failTimes(int times, String userName) {
        for (int attempt = 0; attempt < times; attempt++) {
            fail(userName);
        }
    }
}
