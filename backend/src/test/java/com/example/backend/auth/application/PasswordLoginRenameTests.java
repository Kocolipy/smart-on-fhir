package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
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
 * index belong to — nor, for a Login refused on the standing found then, whom the refusal is
 * recorded and counted against. A Login the authentication itself refused is counted and audited
 * against the User its read found too, never against whoever holds the name by the time the
 * refusal is recorded.
 *
 * <p>For an accepted Login the rename is made by the authentication step itself, right after the
 * real one has accepted the credentials; for a refused one, by the in-memory store right after
 * the authentication's lookup by name ({@link InMemoryScimUserRepository#afterNextNameLookup}),
 * before the comparison. Both are the window a concurrent SCIM write would land in.
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
                users, groups, TestGroupPermissions.superuserOnly());
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
     * A deactivation landing while the password is compared refuses the Login after
     * authentication: the refusal belongs to the User authenticated, not to whoever holds its
     * name by the time it is recorded.
     */
    @Test
    void aRefusalOnStandingIsRecordedForTheAuthenticatedUserWhenAnotherUserTakesItsName() {
        afterAuthentication = () -> {
            deactivateAda();
            renameAdaAndGiveHerNameToBob();
        };

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId)
                .containsExactly(ada);
    }

    @Test
    void aRefusalOnStandingLeavesTheFailureRunOfTheUserWhoTookTheNameAlone() {
        afterAuthentication = () -> {
            deactivateAda();
            renameAdaAndGiveHerNameToBob();
        };

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
    }

    @Test
    void aRefusalOnStandingIsRecordedAsTheAuthenticatedUsersWhenItsNameDisappears() {
        afterAuthentication = () -> {
            deactivateAda();
            users.rename(ada, "ada-renamed");
        };

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(ada, AuditRefusalReason.ACCOUNT_DISABLED.name()));
    }

    // ---- a refusal the authentication made, with a rename landing after its one read --------

    /**
     * A wrong password is counted against the User the authentication read, not whoever holds the
     * name by the time the refusal is recorded: the rename lands right after the lookup by name,
     * before the comparison, the window a concurrent SCIM write would land in.
     */
    @Test
    void aWrongPasswordIsAuditedAgainstTheUserTheAuthenticationReadWhenAnotherTakesItsName() {
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        login.logIn("ada", "wrong-password", "ada-current");

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(ada, AuditRefusalReason.BAD_CREDENTIALS.name()));
    }

    /** The caller is handed the refusal it was recorded as: of the User the read found. */
    @Test
    void aWrongPasswordIsRefusedNamingTheUserTheAuthenticationReadWhenAnotherTakesItsName() {
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        assertThat(login.logIn("ada", "wrong-password", "ada-current").outcome())
                .isEqualTo(new LoginOutcome.PasswordRefused(
                        ada, PasswordRefusalReason.BAD_CREDENTIALS));
    }

    @Test
    void aWrongPasswordLengthensTheRunOfTheUserTheAuthenticationReadWhenAnotherTakesItsName() {
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        login.logIn("ada", "wrong-password", "ada-current");

        assertThat(users.require("ada-renamed").login().failedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void aWrongPasswordLeavesTheRunOfTheUserWhoTookTheNameAlone() {
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        login.logIn("ada", "wrong-password", "ada-current");

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
    }

    /**
     * A wrong password reaching the limit locks the User the authentication read, never the User
     * who took its name.
     */
    @Test
    void aLockoutImposedByAWrongPasswordFallsOnTheUserTheAuthenticationRead() {
        users.given(new ScimUser(ada, ScimIdentities.profile("ada", true),
                new ScimLoginState(hash, 4, null, null), null, ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW, ScimIdentities.NOW));
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        login.logIn("ada", "wrong-password", "ada-current");
        transaction.commit();

        assertThat(List.of(users.require("ada-renamed").login().isLocked(),
                        users.require("ada").login().isLocked()))
                .containsExactly(true, false);
    }

    @Test
    void aLockedUsersRefusalIsAuditedAgainstTheUserTheAuthenticationReadWhenAnotherTakesItsName() {
        lockAda();
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(ada, AuditRefusalReason.ACCOUNT_LOCKED.name()));
    }

    @Test
    void aLockedUsersRefusalLeavesTheRunOfTheUserWhoTookTheNameAlone() {
        lockAda();
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(users.require("ada").login().failedLoginAttempts()).isEqualTo(2);
    }

    /**
     * A User with no password is reported to Spring Security as no User at all (it has no
     * password Login), yet its refusal still names it, by the stable id the one read found.
     */
    @Test
    void aPasswordlessUsersRefusalIsAuditedAgainstTheUserTheAuthenticationReadWhenAnotherTakesItsName() {
        UUID nopass = users.given(ScimIdentities.credentiallessUser("nopass")).id();
        users.afterNextNameLookup(() -> {
            users.rename(nopass, "nopass-renamed");
            users.given(withPassword("nopass", 2));
        });

        login.logIn("nopass", PASSWORD, "nopass-current");

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(nopass, AuditRefusalReason.BAD_CREDENTIALS.name()));
    }

    @Test
    void aPasswordlessUsersRefusalLeavesTheRunOfTheUserWhoTookTheNameAlone() {
        UUID nopass = users.given(ScimIdentities.credentiallessUser("nopass")).id();
        users.afterNextNameLookup(() -> {
            users.rename(nopass, "nopass-renamed");
            users.given(withPassword("nopass", 2));
        });

        login.logIn("nopass", PASSWORD, "nopass-current");

        assertThat(users.require("nopass").login().failedLoginAttempts()).isEqualTo(2);
    }

    /** A name the authentication found no User for is refused against nobody, whoever takes it. */
    @Test
    void anUnknownNameIsAuditedAgainstNobodyWhenAUserTakesTheNameAfterTheRead() {
        users.afterNextNameLookup(() -> users.given(withPassword("grace", 2)));

        login.logIn("grace", PASSWORD, null);

        assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                .extracting(Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(null, AuditRefusalReason.UNKNOWN_ACCOUNT.name()));
    }

    @Test
    void anUnknownNameLeavesTheRunOfAUserWhoTakesTheNameAfterTheReadAlone() {
        users.afterNextNameLookup(() -> users.given(withPassword("grace", 2)));

        login.logIn("grace", PASSWORD, null);

        assertThat(users.require("grace").login().failedLoginAttempts()).isEqualTo(2);
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

    /**
     * An authentication that names its User by stable id but compared no stored credential — one
     * that did not come from the password provider — gives the decision under the lock nothing to
     * confirm, so the Login fails closed rather than skip the check.
     */
    @Test
    void anAuthenticationThatComparedNoStoredCredentialFailsTheLoginClosed() {
        substitute = authenticatedWithoutComparing();

        assertThatIllegalStateException().isThrownBy(() -> login.logIn("ada", PASSWORD, null));
    }

    @Test
    void anAuthenticationThatComparedNoStoredCredentialRecordsNoSuccess() {
        substitute = authenticatedWithoutComparing();

        assertThatIllegalStateException().isThrownBy(() -> login.logIn("ada", PASSWORD, null));

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS)).isEmpty();
    }

    /** Ada's principal as the directory reports it, authenticated with no password compared. */
    private Authentication authenticatedWithoutComparing() {
        LoginPrincipal principal = new LoginIdentityService(
                users, groups, TestGroupPermissions.superuserOnly()).loadUserByUsername("ada");
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    /** Ada leaves the name, and Bob — carrying a failure run of two — is provisioned under it. */
    private void renameAdaAndGiveHerNameToBob() {
        users.rename(ada, "ada-renamed");
        users.given(withPassword("ada", 2));
    }

    private void lockAda() {
        users.given(new ScimUser(ada, ScimIdentities.profile("ada", true),
                new ScimLoginState(hash, 5, NOW), null, ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW, ScimIdentities.NOW));
    }

    private void deactivateAda() {
        ScimUser current = users.require("ada");
        users.given(ScimIdentities.withActive(current, false, current.login()));
    }

    private ScimUser withPassword(String userName, int failedAttempts) {
        return new ScimUser(UUID.randomUUID(), ScimIdentities.profile(userName, true),
                new ScimLoginState(hash, failedAttempts, null, null),
                null, ScimUser.INITIAL_VERSION, ScimIdentities.NOW, ScimIdentities.NOW);
    }
}
