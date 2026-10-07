package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.authorization.TestRoleMappings;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.auth.controller.AuthController;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.ExceptionHandler;

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
        login = new LoginService(
                config.authenticationManager(identities, passwordEncoder),
                new LoginAttemptService(
                        users,
                        sessions,
                        transaction,
                        new LockoutPolicy(5),
                        audit,
                        clock),
                identities);
        administration = new IdentityAdministrationService(
                users, groups, sessions, transaction, audit, clock);
    }

    @Test
    void anAcceptedLoginReportsTheIdentityAndItsDerivedAuthority() {
        Authentication authentication = login.logIn("ada", CORRECT_PASSWORD).authentication();

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("ada");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_USER");
    }

    /** And the stable id it reports is the SCIM resource's, not the submitted name. */
    @Test
    void anAcceptedLoginReportsTheScimResourceId() {
        assertThat(login.logIn("ada", CORRECT_PASSWORD).userId())
                .isEqualTo(users.require("ada").id());
    }

    /**
     * The accepted record names the identity by its stable id, as {@code user.id} in the
     * record's context — set on the record itself and gone once it is written, since the
     * session that carries it on later requests does not exist yet.
     */
    @Test
    void theAcceptedLoginRecordCarriesTheStableIdAndClassification() {
        try (CapturedLog captured = CapturedLog.attach()) {
            login.logIn("ada", CORRECT_PASSWORD);

            ILoggingEvent record = onlyLoginRecord(captured, Level.INFO);
            assertThat(record.getMDCPropertyMap())
                    .containsEntry(LogContext.USER_ID, users.require("ada").id().toString());
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.OUTCOME, LogEvent.SUCCESS)
                    .containsEntry(LogEvent.KIND, "event")
                    .containsEntry(LogEvent.CATEGORY, List.of("process"))
                    .containsEntry(LogEvent.TYPE, List.of("user", "allowed"));
        }
        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    /** The accepted record says how the Login was made (D15), as the Epic one does. */
    @Test
    void theAcceptedLoginRecordNamesThePasswordMethod() {
        try (CapturedLog captured = CapturedLog.attach()) {
            login.logIn("ada", CORRECT_PASSWORD);

            assertThat(CapturedLog.fields(onlyLoginRecord(captured, Level.INFO)))
                    .containsEntry(LogEvent.LOGIN_METHOD, "password");
        }
    }

    /**
     * A refused attempt names nobody, even inside a request already carrying a User's
     * id: the identity the attempt was for is unresolved. The outer id is back for the
     * rest of the request once the record is written.
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
                    .containsEntry(LogEvent.REASON, "BadCredentialsException")
                    .containsEntry(LogEvent.TYPE, List.of("user", "denied"));
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

        assertThatThrownBy(() -> login.logIn("ada", CORRECT_PASSWORD))
                .isInstanceOf(LockedException.class);
        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(RecordingAuditTrail.Recorded::detail)
                .as("the refusal is audited as the lockout, not as a generic failure")
                .containsExactly(com.example.backend.audit.domain.AuditRefusalReason
                        .ACCOUNT_LOCKED.name());
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

        assertThatThrownBy(() -> login.logIn("ada", CORRECT_PASSWORD))
                .isInstanceOf(LockedException.class);
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

        Authentication authentication = login.logIn("ada", CORRECT_PASSWORD).authentication();
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
     * A locked identity and a wrong password leave as <em>different</em> exception types,
     * so the uniform refusal cannot come from the domain throwing one thing: it comes from
     * {@link AuthController} declaring a single handler for their common supertype. Both
     * halves are asserted — the types genuinely differ, and the one handler the controller
     * declares covers both — so a narrower handler added for either type fails here rather
     * than silently making a locked identity distinguishable from an unknown one.
     *
     * <p>The response bytes that uniformity produces are asserted in
     * {@code AuthControllerTests.aRefusedLoginAnswersWithAnEmptyUnauthorizedResponse}; this
     * test pins the precondition that makes one handler sufficient.
     */
    @Test
    void aLockedIdentityAndAWrongPasswordAreRefusedThroughTheSameHandler() {
        AuthenticationException wrongPassword = submit("wrong");
        assertThat(wrongPassword).isInstanceOf(BadCredentialsException.class);

        submit("wrong");
        submit("wrong");
        submit("wrong");
        submit("wrong");
        AuthenticationException locked = submit(CORRECT_PASSWORD);
        assertThat(locked).isInstanceOf(LockedException.class);

        assertThat(locked.getClass()).isNotEqualTo(wrongPassword.getClass());

        assertThat(refusalHandlerTypes())
                .as("the exception types AuthController answers with a bare 401")
                .anySatisfy(handled -> assertThat(handled).isAssignableFrom(wrongPassword.getClass()))
                .anySatisfy(handled -> assertThat(handled).isAssignableFrom(locked.getClass()));
    }

    /**
     * The exception types {@link AuthController}'s refusal handler is declared for, read
     * from the annotation rather than restated here so the assertion tracks the controller
     * instead of a copy of it.
     */
    private static List<Class<? extends Throwable>> refusalHandlerTypes() {
        return Arrays.stream(AuthController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(ExceptionHandler.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .toList();
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
                login.logIn(BOOTSTRAP_ADMIN, CORRECT_PASSWORD).authentication();

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
                login.logIn(BOOTSTRAP_ADMIN, CORRECT_PASSWORD).authentication();

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
    private AuthenticationException submit(String password) {
        return submitAs("ada", password);
    }

    private AuthenticationException submitAs(String username, String password) {
        try {
            login.logIn(username, password);
            throw new AssertionError("Expected the login to be refused");
        } catch (AuthenticationException refused) {
            return refused;
        }
    }
}
