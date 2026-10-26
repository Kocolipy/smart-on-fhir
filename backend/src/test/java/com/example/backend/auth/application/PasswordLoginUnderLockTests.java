package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.RecordingLoginCounts;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A password Login is decided once, under the User's lock, on the state stored there: the
 * credential the authentication verified must still be the stored one, and whether the session is
 * confined is read from the same state that decides the dormancy basis.
 *
 * <p>The authentication reads the User by name, then compares the password — an Argon2id
 * verification long enough for a connector's write to commit in between. The in-memory store's
 * {@link InMemoryScimUserRepository#afterNextNameLookup} lands that write right after the read,
 * before the comparison and the lock: the window a concurrent SCIM write lands in.
 */
class PasswordLoginUnderLockTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    private static final Instant EARLIER = Instant.parse("2026-06-01T00:00:00Z");

    private static final String PASSWORD = "correct-password";

    private static final int LIMIT = 5;

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private PasswordEncoder passwordEncoder;

    private String hash;

    private UUID ada;

    private LoginService login;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        passwordEncoder = config.passwordEncoder();
        hash = passwordEncoder.encode(PASSWORD);
        ada = users.given(adaWith(new ScimLoginState(hash, 0, null, null, EARLIER, null))).id();
        LoginIdentityService identities = new LoginIdentityService(
                users, groups, TestGroupPermissions.superuserOnly());
        SessionRevocationService revocation = new SessionRevocationService(
                sessions, transaction, audit, new RecordingOperationalAlerts());
        LoginAttemptService attempts = new LoginAttemptService(
                users,
                LoginStateModules.over(
                        users, revocation, new LockoutPolicy(LIMIT), audit, new MutableClock(NOW)),
                revocation,
                audit);
        login = new LoginService(config.authenticationManager(identities, passwordEncoder),
                attempts, identities, RecordingLoginCounts.uncounted(attempts, audit));
    }

    // ---- the credential, confirmed under the lock --------------------------------------------

    /**
     * A connector replaced the password while the old one was being compared: at the moment of
     * the decision the submitted password is simply wrong, so the Login is refused as one.
     */
    @Test
    void aLoginWhosePasswordIsReplacedDuringTheComparisonIsRefusedAsBadCredentials() {
        users.afterNextNameLookup(this::aConnectorReplacesAdasPassword);

        LoginDecision decision = login.logIn("ada", PASSWORD, "ada-current");

        assertThat(decision.outcome())
                .isEqualTo(new PasswordRefused(ada, PasswordRefusalReason.BAD_CREDENTIALS));
    }

    @Test
    void aLoginWhosePasswordIsReplacedDuringTheComparisonIssuesNoSession() {
        users.afterNextNameLookup(this::aConnectorReplacesAdasPassword);

        assertThat(login.logIn("ada", PASSWORD, "ada-current").accepted()).isEmpty();
    }

    @Test
    void aLoginWhosePasswordIsReplacedDuringTheComparisonIsAuditedAsOneFailureNamingTheUser() {
        users.afterNextNameLookup(this::aConnectorReplacesAdasPassword);

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.recorded())
                .extracting(Recorded::operation, Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(AuditOperation.LOGIN_FAILURE, ada,
                        AuditRefusalReason.BAD_CREDENTIALS.name()));
    }

    @Test
    void aLoginWhosePasswordIsReplacedDuringTheComparisonIsCountedOnTheFailureRun() {
        users.afterNextNameLookup(this::aConnectorReplacesAdasPassword);

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(1);
    }

    /** Counted exactly as a rejected password is: the one reaching the limit imposes a Lockout. */
    @Test
    void aLoginWhosePasswordIsReplacedDuringTheComparisonLocksTheUserAtTheLimit() {
        users.given(adaWith(new ScimLoginState(hash, LIMIT - 1, null, null, EARLIER, null)));
        users.afterNextNameLookup(this::aConnectorReplacesAdasPassword);

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.of(AuditOperation.LOCKOUT_SET))
                .extracting(Recorded::subjectId)
                .containsExactly(ada);
    }

    /**
     * Ada's own change in another session leaves no change required, so only the credential
     * check stands between the old password and a Login that would move the basis.
     */
    @Test
    void aLoginWhosePasswordIsChangedElsewhereDuringTheComparisonLeavesTheDormancyBasis() {
        users.afterNextNameLookup(this::adaChangesHerPasswordElsewhere);

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(users.require("ada").login().lastAuthenticatedAt()).isEqualTo(EARLIER);
    }

    @Test
    void aLoginWhosePasswordIsReplacedDuringTheComparisonEndsNoOtherSession() {
        sessions.open(ada, "ada-current");
        sessions.open(ada, "ada-elsewhere");
        users.afterNextNameLookup(this::aConnectorReplacesAdasPassword);

        login.logIn("ada", PASSWORD, "ada-current");
        transaction.commit();

        assertThat(sessions.sessionsOf(ada))
                .containsExactlyInAnyOrder("ada-current", "ada-elsewhere");
    }

    /**
     * The same password written again is a new credential: the hash is salted per write, so the
     * Login compared against one that is no longer stored.
     */
    @Test
    void aLoginWhosePasswordIsRewrittenToTheSameValueDuringTheComparisonIsRefused() {
        users.afterNextNameLookup(() -> users.given(adaWith(
                new ScimLoginState(passwordEncoder.encode(PASSWORD), 0, null, null, EARLIER,
                        null))));

        assertThat(login.logIn("ada", PASSWORD, "ada-current").outcome())
                .isEqualTo(new PasswordRefused(ada, PasswordRefusalReason.BAD_CREDENTIALS));
    }

    /** With nothing written in between, the same Login is accepted: the check refuses no one else. */
    @Test
    void aLoginWhosePasswordStandsIsAccepted() {
        assertThat(login.logIn("ada", PASSWORD, "ada-current").accepted()).isPresent();
    }

    // ---- confinement, from the state the decision read ---------------------------------------

    /**
     * A change required since the read — with the same credential — confines the session: the
     * authorities come from the state under the lock, the one that left the dormancy basis
     * unmoved, not from the read before it.
     */
    @Test
    void aChangeRequiredSinceTheReadConfinesTheIssuedSession() {
        users.afterNextNameLookup(() -> users.given(adaWith(
                new ScimLoginState(hash, 0, null, null, EARLIER, NOW))));

        assertThat(login.logIn("ada", PASSWORD, "ada-current").accepted().orElseThrow()
                        .authentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY)
                .doesNotContain("ROLE_USER", "patient:read");
    }

    /** The confined principal the session carries holds what the authentication does, no more. */
    @Test
    void aChangeRequiredSinceTheReadConfinesTheIssuedPrincipal() {
        users.afterNextNameLookup(() -> users.given(adaWith(
                new ScimLoginState(hash, 0, null, null, EARLIER, NOW))));

        assertThat(((LoginPrincipal) login.logIn("ada", PASSWORD, "ada-current").accepted()
                        .orElseThrow().authentication().getPrincipal()).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY);
    }

    @Test
    void anUnflaggedUsersSessionIsIssuedUnconfined() {
        assertThat(login.logIn("ada", PASSWORD, "ada-current").accepted().orElseThrow()
                        .authentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .doesNotContain(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY)
                .contains("ROLE_USER");
    }

    /** As Ada's own completed change stores one: a new credential, and no change owed. */
    private void adaChangesHerPasswordElsewhere() {
        users.given(adaWith(new ScimLoginState(
                passwordEncoder.encode("adas-new-password"), 0, null, null, EARLIER, null)));
    }

    /** As a connector's PUT stores a password: a new credential, and a change required. */
    private void aConnectorReplacesAdasPassword() {
        users.given(adaWith(new ScimLoginState(
                passwordEncoder.encode("connector-set-password"),
                users.require("ada").login().failedLoginAttempts(), null, null, EARLIER, NOW)));
    }

    private ScimUser adaWith(ScimLoginState login) {
        return new ScimUser(ada != null ? ada : UUID.randomUUID(),
                ScimIdentities.profile("ada", true), login, null, ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW, ScimIdentities.NOW);
    }
}
