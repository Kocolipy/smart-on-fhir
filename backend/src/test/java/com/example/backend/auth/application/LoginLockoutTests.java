package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.authorization.TestRoleMappings;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.RecordingLoginCounts;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginService.AcceptedLogin;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The lockout story over the real authentication chain: the login module, the attempt
 * counter, the administration use case that lifts a lock, and the SCIM User store Spring
 * Security reads its {@code UserDetails} from. Wired by hand rather than through a Spring
 * context so the clock can be moved and no database is needed.
 *
 * <p>Driven through {@link LoginService} rather than through the login endpoint, because
 * that is the module the counting belongs to: any entry point that authenticates submitted
 * credentials goes through here and gets this behaviour, and none of it depends on there
 * being an HTTP session to create. What the endpoint adds on top — the session, the CSRF
 * token, the bare {@code 401} — is asserted in {@code AuthControllerTests}.
 *
 * <p>What a counted failure implies once it reaches the limit — the Lockout itself, its
 * {@code LOCKOUT_SET}, the revocation of every Session, the Bootstrap Admin's exemption — is
 * {@code FailureCounter}'s and is asserted in {@code FailureCounterTests}; this story keeps what
 * only the whole chain can show: that refusals are counted, that a lock is enforced on the
 * correct password, and that only an Unlock lifts it.
 *
 * <p>The recovery identity is now recognised by its reservation marker, so it is seeded
 * through {@code createReserved} and is an administrator by membership of the reserved
 * Admin group rather than by a role column.
 */
class LoginLockoutTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    /**
     * Ten years. The lockout used to lift after a configured window, so a small advance
     * could not tell "permanent" from "long"; this one is past any window a deployment
     * could plausibly have set.
     */
    private static final Duration A_LONG_TIME = Duration.ofDays(3650);

    private static final String CORRECT_PASSWORD = "correct-password";

    private static final String BOOTSTRAP_ADMIN = "recovery-admin";

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private LoginService login;

    private IdentityAdministrationService administration;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();
        // Encoded once and shared: Argon2id at the configured parameters is deliberately
        // expensive, and both identities are given the same password on purpose.
        String hash = passwordEncoder.encode(CORRECT_PASSWORD);
        users.given(withPassword("ada", hash));
        ScimUser recovery = users.createReserved(
                withPassword(BOOTSTRAP_ADMIN, hash), ReservedResourceName.BOOTSTRAP_ADMIN);
        groups.createReserved(
                ScimIdentities.group("Admins", recovery), ReservedResourceName.ADMIN_GROUP);

        LoginIdentityService identities =
                new LoginIdentityService(users, groups, passwordEncoder, TestRoleMappings.superuserOnly());
        LoginAttemptService attempts = new LoginAttemptService(
                users,
                new SessionRevocationService(
                        sessions,
                        transaction,
                        audit,
                        new RecordingOperationalAlerts()),
                new LockoutPolicy(5),
                audit,
                clock);
        login = new LoginService(
                config.authenticationManager(identities, passwordEncoder),
                attempts,
                identities,
                RecordingLoginCounts.uncounted(attempts, audit));
        administration = new IdentityAdministrationService(
                users,
                groups,
                new SessionRevocationService(
                        sessions,
                        transaction,
                        audit,
                        new RecordingOperationalAlerts()),
                audit,
                clock);
    }

    @Test
    void anAcceptedLoginReportsTheIdentityAndItsDerivedAuthority() {
        Authentication authentication = login.logIn("ada", CORRECT_PASSWORD).accepted().orElseThrow().authentication();

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("ada");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_USER");
    }

    /** And the stable id it reports is the SCIM resource's, not the submitted name. */
    @Test
    void anAcceptedLoginReportsTheScimResourceId() {
        assertThat(login.logIn("ada", CORRECT_PASSWORD).accepted().orElseThrow().userId())
                .isEqualTo(users.require("ada").id());
    }

    /**
     * An acceptance is recorded once the session is signed in, by the web adapter's Login
     * completion ({@code LoginCompletionTests}): the decision writes no {@code user-authentication}
     * record of it, so a Login that never gets its session is not logged a success.
     */
    @Test
    void anAcceptedLoginIsNotRecordedByTheDecision() {
        try (CapturedLog captured = CapturedLog.attach()) {
            accepted("ada");

            assertThat(captured.withAction(Level.INFO, LogEvent.ACTION, "user-authentication"))
                    .isEmpty();
        }
    }

    /** The decision hands back what the accepted Login is recorded as once it is signed in. */
    @Test
    void anAcceptedLoginEndsSignedInAsItsUserByPassword() {
        assertThat(login.logIn("ada", CORRECT_PASSWORD).outcome())
                .isEqualTo(LoginOutcome.SignedIn.password(users.require("ada").id()));
    }

    /**
     * A refused attempt names nobody, even inside a request already carrying a User's
     * id: the identity the attempt was for is unresolved. Nor does it say why it was
     * refused (Logging §2.2): the reason tells whether the account exists, so it is the
     * audit trail's alone. The outer id is back for the rest of the request once the
     * record is written.
     */
    @Test
    void theRefusedLoginRecordCarriesNoUserIdEvenInsideAnAuthenticatedRequest() {
        UUID sessionUser = users.require(BOOTSTRAP_ADMIN).id();
        try (CapturedLog captured = CapturedLog.attach();
                LogContext.Scope request = LogContext.userId(sessionUser)) {
            submit("wrong");

            ILoggingEvent record = onlyLoginRecord(captured, Level.WARN);
            assertThat(record.getMDCPropertyMap()).doesNotContainKey(LogContext.USER_ID);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.OUTCOME, LogEvent.FAILURE)
                    .containsEntry(LogEvent.TYPE, List.of("user", "denied"))
                    .doesNotContainKey(LogEvent.REASON);
            assertThat(MDC.get(LogContext.USER_ID)).isEqualTo(sessionUser.toString());
        } finally {
            MDC.clear();
        }
    }

    private static ILoggingEvent onlyLoginRecord(CapturedLog captured, Level level) {
        List<ILoggingEvent> records =
                captured.withAction(level, LogEvent.ACTION, "user-authentication");
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getLevel()).isEqualTo(level);
        return records.getFirst();
    }

    @Test
    void anAcceptedLoginResetsTheFailureCount() {
        submit("wrong");
        submit("wrong");

        login.logIn("ada", CORRECT_PASSWORD);

        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
        assertThat(users.require("ada").login().lockedAt()).isNull();
    }

    @Test
    void eachRefusedLoginIncrementsTheFailureCount() {
        submit("wrong");
        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(1);

        submit("wrong");
        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
    }

    /**
     * The point of the lockout: while it holds, the right password is refused too, and no
     * authentication is handed back for it.
     */
    @Test
    void aLockedIdentityIsRefusedEvenWithTheCorrectPassword() {
        lockTheIdentity();
        audit.reset();

        assertThat(submit(CORRECT_PASSWORD).reason()).isEqualTo(AuditRefusalReason.ACCOUNT_LOCKED);
        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .as("the refusal is audited as the lockout, not as a generic failure")
                .containsExactly(com.example.backend.audit.domain.AuditRefusalReason
                        .ACCOUNT_LOCKED.name());
    }

    /**
     * Logging §2.2: the operational record of a locked account's refusal is exactly a wrong
     * password's — it would otherwise tell its reader that the account exists, and is locked.
     * The lockout is the audit trail's to say, above.
     */
    @Test
    void aLockedIdentitysRefusalIsLoggedExactlyAsAWrongPasswordIs() {
        Map<String, Object> wrongPassword;
        try (CapturedLog captured = CapturedLog.attach()) {
            submit("wrong");
            wrongPassword = CapturedLog.fields(onlyLoginRecord(captured, Level.WARN));
        }
        lockTheIdentity();

        try (CapturedLog captured = CapturedLog.attach()) {
            submit(CORRECT_PASSWORD);

            ILoggingEvent locked = onlyLoginRecord(captured, Level.WARN);
            assertThat(locked.getFormattedMessage()).isEqualTo("Login refused");
            assertThat(CapturedLog.fields(locked)).isEqualTo(wrongPassword);
        }
    }

    @Test
    void attemptsDuringTheLockoutDoNotDeepenIt() {
        lockTheIdentity();
        Instant lockedAt = users.require("ada").login().lockedAt();

        clock.advanceBy(Duration.ofMinutes(1));
        submit("wrong");
        submit(CORRECT_PASSWORD);

        assertThat(users.require("ada").login().lockedAt()).isEqualTo(lockedAt);
        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(5);
    }

    /**
     * The criterion the whole change exists for: no clock advance is a lift, so the correct
     * password is still refused a decade later.
     */
    @Test
    void theCorrectPasswordIsStillRefusedHoweverLongTheLockoutHasStood() {
        lockTheIdentity();

        clock.advanceBy(A_LONG_TIME);

        assertThat(submit(CORRECT_PASSWORD).reason()).isEqualTo(AuditRefusalReason.ACCOUNT_LOCKED);
        assertThat(users.require("ada").login().isLocked()).isTrue();
    }

    /**
     * Unlock is the whole mechanism: the password was never changed, so an accepted login
     * afterward proves the lock and only the lock was what refused it.
     */
    @Test
    void anAdministratorsUnlockIsTheOnlyThingThatLetsTheIdentityBackIn() {
        lockTheIdentity();
        clock.advanceBy(A_LONG_TIME);

        administration.unlock(users.require("ada").id(), BOOTSTRAP_ADMIN);

        Authentication authentication = login.logIn("ada", CORRECT_PASSWORD).accepted().orElseThrow().authentication();
        assertThat(authentication.getName()).isEqualTo("ada");
        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
        assertThat(users.require("ada").login().lockedAt()).isNull();
    }

    /** And the lift is recorded against the administrator who performed it. */
    @Test
    void theUnlockIsAuditedWithItsAdministratorAsActor() {
        lockTheIdentity();
        audit.reset();

        administration.unlock(users.require("ada").id(), BOOTSTRAP_ADMIN);

        assertThat(audit.of(AuditOperation.LOCKOUT_LIFT))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId())
                            .isEqualTo(users.require(BOOTSTRAP_ADMIN).id());
                    assertThat(event.subjectId()).isEqualTo(users.require("ada").id());
                });
    }

    /**
     * A locked identity and a wrong password end in the same kind of decision, a password
     * refusal, told apart only by the reason the audit trail records. The web adapter answers
     * every refusal with one bare {@code 401}, so it cannot make a locked identity
     * distinguishable from a wrong password; the bytes of that answer are asserted in
     * {@code AuthControllerTests.aLockedAccountsRefusalAnswersExactlyAsAWrongPasswordsDoes}.
     */
    @Test
    void aLockedIdentityAndAWrongPasswordEndInTheSameRefusalWithDifferentReasons() {
        PasswordRefused wrongPassword = submit("wrong");
        submit("wrong");
        submit("wrong");
        submit("wrong");
        submit("wrong");

        PasswordRefused locked = submit(CORRECT_PASSWORD);

        assertThat(List.of(wrongPassword, locked))
                .extracting(PasswordRefused::reason)
                .containsExactly(
                        AuditRefusalReason.BAD_CREDENTIALS, AuditRefusalReason.ACCOUNT_LOCKED);
    }

    @Test
    void anUnknownUsernameLeavesNothingBehind() {
        submitAs("nobody", "whatever");

        assertThat(users.findByNormalizedUserName(NormalizedUserName.of("nobody"))).isEmpty();
        assertThat(users.require("ada").login().failedLoginAttempts()).isZero();
    }

    // The Bootstrap Admin, the one principal a failure run cannot close

    /**
     * Well past the threshold, the recovery identity still logs in. Without this the
     * permanent lockout would make an unauthenticated attacker able to brick the
     * deployment.
     */
    @Test
    void theBootstrapAdminStillLogsInAfterFarMoreFailuresThanTheThreshold() {
        for (int attempt = 0; attempt < 10; attempt++) {
            submitAs(BOOTSTRAP_ADMIN, "wrong");
        }

        assertThat(users.require(BOOTSTRAP_ADMIN).login().isLocked()).isFalse();
        assertThat(users.require(BOOTSTRAP_ADMIN).login().failedLoginAttempts()).isEqualTo(10);

        Authentication authentication =
                login.logIn(BOOTSTRAP_ADMIN, CORRECT_PASSWORD).accepted().orElseThrow().authentication();

        assertThat(authentication.getName()).isEqualTo(BOOTSTRAP_ADMIN);
        assertThat(users.require(BOOTSTRAP_ADMIN).login().failedLoginAttempts()).isZero();
    }

    /**
     * And it logs in as an administrator, by membership of the reserved Admin group rather
     * than by a role column — with {@code ADMIN} ahead of {@code USER}, which is what a
     * caller reading a single role off the authorities depends on.
     */
    @Test
    void theBootstrapAdminLogsInAsAnAdministratorThroughItsGroupMembership() {
        Authentication authentication =
                login.logIn(BOOTSTRAP_ADMIN, CORRECT_PASSWORD).accepted().orElseThrow().authentication();

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                // Filtered to the roles: the authenticated token also carries Spring
                // Security's own FACTOR_PASSWORD authority, which is not a derived role and
                // says how the principal authenticated rather than what it may do.
                .filteredOn(authority -> authority.startsWith("ROLE_"))
                .containsExactly("ROLE_USER");
    }

    @Test
    void everyBootstrapAdminFailureIsStillAudited() {
        for (int attempt = 0; attempt < 10; attempt++) {
            submitAs(BOOTSTRAP_ADMIN, "wrong");
        }

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE)).hasSize(10)
                .allSatisfy(event -> assertThat(event.subjectId())
                        .isEqualTo(users.require(BOOTSTRAP_ADMIN).id()));
    }

    private static ScimUser withPassword(String userName, String passwordHash) {
        return ScimUser.created(
                UUID.randomUUID(),
                ScimIdentities.profile(userName, true),
                passwordHash,
                ScimIdentities.NOW);
    }

    private void lockTheIdentity() {
        failFiveTimes();
        assertThat(users.require("ada").login().isLocked()).isTrue();
    }

    private void failFiveTimes() {
        for (int attempt = 0; attempt < 5; attempt++) {
            submit("wrong");
        }
    }

    /** Submits a login expected to be refused, returning the refusal. */
    private PasswordRefused submit(String password) {
        return submitAs("ada", password);
    }

    private PasswordRefused submitAs(String username, String password) {
        LoginDecision decision = login.logIn(username, password);
        assertThat(decision.accepted()).as("the login was refused").isEmpty();
        return (PasswordRefused) decision.outcome();
    }

    /** Submits {@code username}'s correct password, expecting the Login to be accepted. */
    private AcceptedLogin accepted(String username) {
        return login.logIn(username, CORRECT_PASSWORD).accepted().orElseThrow();
    }
}
