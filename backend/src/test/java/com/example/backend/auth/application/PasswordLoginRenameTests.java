package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.auth.RecordingLoginCounts;
import com.example.backend.auth.application.LoginService.AcceptedLogin;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A password Login reads its User once, when it authenticates, and works from that User's stable
 * id afterwards: a directory rename landing between authentication and the recording of the
 * success changes nothing about whom the success, the One session per User sweep and the session
 * index belong to.
 *
 * <p>The rename is made by the authentication step itself, right after the real one has accepted
 * the credentials, which is the window a concurrent SCIM write would land in.
 */
class PasswordLoginRenameTests {

    private static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    private static final String PASSWORD = "correct-password";

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private String hash;

    private UUID ada;

    /** What lands in the directory once the credentials are accepted; nothing by default. */
    private Runnable afterAuthentication = () -> { };

    /** What authentication answers in place of the real one, when a test sets it. */
    private Authentication substitute;

    private LoginService login;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();
        hash = passwordEncoder.encode(PASSWORD);
        ada = users.given(withPassword("ada", 0)).id();
        LoginIdentityService identities = new LoginIdentityService(
                users, groups, passwordEncoder, TestGroupPermissions.superuserOnly());
        SessionRevocationService revocation = new SessionRevocationService(
                sessions, transaction, audit, new RecordingOperationalAlerts());
        LoginAttemptService attempts = new LoginAttemptService(
                users,
                LoginStateModules.over(
                        users, revocation, new LockoutPolicy(5), audit, new MutableClock(NOW)),
                revocation,
                audit);
        AuthenticationManager real = config.authenticationManager(identities, passwordEncoder);
        AuthenticationManager racing = request -> {
            Authentication authenticated =
                    substitute != null ? substitute : real.authenticate(request);
            afterAuthentication.run();
            return authenticated;
        };
        login = new LoginService(racing, attempts, identities,
                RecordingLoginCounts.uncounted(attempts, audit));
    }

    @Test
    void theSuccessIsRecordedForTheAuthenticatedUserWhenAnotherUserTakesItsName() {
        afterAuthentication = this::renameAdaAndGiveHerNameToBob;

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(Recorded::subjectId)
                .containsExactly(ada);
    }

    @Test
    void theSuccessIsRecordedWhenTheAuthenticatedNameDisappears() {
        afterAuthentication = () -> users.rename(ada, "ada-renamed");

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(Recorded::subjectId)
                .containsExactly(ada);
    }

    @Test
    void theSessionIndexNamesTheAuthenticatedUserWhenAnotherUserTakesItsName() {
        afterAuthentication = this::renameAdaAndGiveHerNameToBob;

        AcceptedLogin accepted = login.logIn("ada", PASSWORD, "ada-current")
                .accepted().orElseThrow();

        assertThat(accepted.userId()).isEqualTo(ada);
    }

    @Test
    void theOneSessionSweepEndsTheAuthenticatedUsersOtherSessionsWhenAnotherUserTakesItsName() {
        afterAuthentication = this::renameAdaAndGiveHerNameToBob;

        login.logIn("ada", PASSWORD, "ada-current");
        transaction.commit();

        assertThat(sessions.loginRevocations()).containsExactly(ada);
    }

    @Test
    void theUserWhoTookTheNameKeepsItsFailureRun() {
        afterAuthentication = this::renameAdaAndGiveHerNameToBob;

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
    }

    /**
     * An authentication whose principal names its User only by {@code userName} gives the Login
     * nothing to work from but the name, which it refuses to look up again: the Login fails closed.
     */
    @Test
    void anAuthenticationCarryingNoStableIdFailsTheLoginClosed() {
        substitute = UsernamePasswordAuthenticationToken.authenticated(
                "ada", null, List.of());

        assertThatIllegalStateException().isThrownBy(() -> login.logIn("ada", PASSWORD, null));
    }

    @Test
    void anAuthenticationCarryingNoStableIdRecordsNoSuccess() {
        substitute = UsernamePasswordAuthenticationToken.authenticated(
                "ada", null, List.of());

        assertThatIllegalStateException().isThrownBy(() -> login.logIn("ada", PASSWORD, null));

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS)).isEmpty();
    }

    /** Ada leaves the name, and Bob — carrying a failure run of two — is provisioned under it. */
    private void renameAdaAndGiveHerNameToBob() {
        users.rename(ada, "ada-renamed");
        users.given(withPassword("ada", 2));
    }

    private ScimUser withPassword(String userName, int failedAttempts) {
        return new ScimUser(UUID.randomUUID(), ScimIdentities.profile(userName, true),
                new ScimLoginState(hash, failedAttempts, null, null),
                null, ScimUser.INITIAL_VERSION, ScimIdentities.NOW, ScimIdentities.NOW);
    }
}
