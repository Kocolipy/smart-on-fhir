package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.LoginHarness;
import com.example.backend.auth.application.LoginService.AcceptedLogin;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

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
 * real one has accepted the credentials — {@link LoginHarness} wraps it so; for a refused one, by
 * the in-memory store right after the authentication's lookup by name
 * ({@link InMemoryScimUserRepository#afterNextNameLookup}), before the comparison. Both are the
 * window a concurrent SCIM write would land in. Each race is one scenario, every effect of it
 * asserted softly so each is still reported on its own.
 */
class PasswordLoginRenameTests {

    private static final Instant NOW = LoginHarness.NOW;

    private static final String PASSWORD = "correct-password";

    /** What lands in the directory once the credentials are accepted; nothing by default. */
    private Runnable afterAuthentication = () -> { };

    /** What authentication answers in place of the real one, when a test sets it. */
    private Authentication substitute;

    private final LoginHarness harness = LoginHarness.builder()
            .authentication(real -> request -> {
                Authentication authenticated =
                        substitute != null ? substitute : real.authenticate(request);
                afterAuthentication.run();
                return authenticated;
            })
            .build();
    private final InMemoryScimUserRepository users = harness.users();
    private final RecordingAuditTrail audit = harness.audit();
    private final LoginService login = harness.login();

    private final String hash = harness.passwordEncoder().encode(PASSWORD);

    private final UUID ada = users.given(withPassword("ada", 0)).id();

    // ---- an accepted Login, with a rename landing after the authentication ------------------

    /**
     * The success, the session index and the One session per User sweep all belong to the User
     * authenticated, and the User who took its name keeps its own failure run.
     */
    @Test
    void anAcceptedLoginBelongsToTheAuthenticatedUserWhenAnotherUserTakesItsName() {
        afterAuthentication = this::renameAdaAndGiveHerNameToBob;

        LoginDecision decision = login.logIn("ada", PASSWORD, "ada-current");
        harness.transaction().commit();

        assertSoftly(softly -> {
            softly.assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                    .as("the success is recorded for the authenticated User")
                    .extracting(Recorded::subjectId)
                    .containsExactly(ada);
            softly.assertThat(decision.accepted().map(AcceptedLogin::userId))
                    .as("the session index names the authenticated User")
                    .contains(ada);
            softly.assertThat(harness.sessions().loginRevocations())
                    .as("the sweep ends the authenticated User's other sessions")
                    .containsExactly(ada);
            softly.assertThat(users.require("ada").login().failedLoginAttempts())
                    .as("the User who took the name keeps its failure run")
                    .isEqualTo(2);
        });
    }

    @Test
    void theSuccessIsRecordedWhenTheAuthenticatedNameDisappears() {
        afterAuthentication = () -> users.rename(ada, "ada-renamed");

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                .extracting(Recorded::subjectId)
                .containsExactly(ada);
    }

    /**
     * A deactivation landing while the password is compared refuses the Login after
     * authentication: the refusal belongs to the User authenticated, not to whoever holds its
     * name by the time it is recorded, and leaves that User's failure run alone.
     */
    @Test
    void aRefusalOnStandingBelongsToTheAuthenticatedUserWhenAnotherUserTakesItsName() {
        afterAuthentication = () -> {
            deactivateAda();
            renameAdaAndGiveHerNameToBob();
        };

        login.logIn("ada", PASSWORD, "ada-current");

        assertSoftly(softly -> {
            softly.assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                    .as("recorded for the authenticated User")
                    .extracting(Recorded::subjectId)
                    .containsExactly(ada);
            softly.assertThat(users.require("ada").login().failedLoginAttempts())
                    .as("the User who took the name keeps its failure run")
                    .isEqualTo(2);
        });
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
     * before the comparison, the window a concurrent SCIM write would land in. The caller is
     * handed the refusal it was recorded as: of the User the read found.
     */
    @Test
    void aWrongPasswordIsHeldAgainstTheUserTheAuthenticationReadWhenAnotherTakesItsName() {
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        LoginDecision decision = login.logIn("ada", "wrong-password", "ada-current");

        assertSoftly(softly -> {
            softly.assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                    .as("audited against the User the read found")
                    .extracting(Recorded::subjectId, Recorded::detail)
                    .containsExactly(tuple(ada, AuditRefusalReason.BAD_CREDENTIALS.name()));
            softly.assertThat(decision.outcome())
                    .as("refused naming the User the read found")
                    .isEqualTo(new LoginOutcome.PasswordRefused(
                            ada, PasswordRefusalReason.BAD_CREDENTIALS));
            softly.assertThat(users.require("ada-renamed").login().failedLoginAttempts())
                    .as("lengthens the run of the User the read found")
                    .isEqualTo(1);
            softly.assertThat(users.require("ada").login().failedLoginAttempts())
                    .as("leaves the run of the User who took the name alone")
                    .isEqualTo(2);
        });
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
        harness.transaction().commit();

        assertThat(List.of(users.require("ada-renamed").login().isLocked(),
                        users.require("ada").login().isLocked()))
                .containsExactly(true, false);
    }

    @Test
    void aLockedUsersRefusalIsHeldAgainstTheUserTheAuthenticationReadWhenAnotherTakesItsName() {
        lockAda();
        users.afterNextNameLookup(this::renameAdaAndGiveHerNameToBob);

        login.logIn("ada", PASSWORD, "ada-current");

        assertSoftly(softly -> {
            softly.assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                    .as("audited against the User the read found")
                    .extracting(Recorded::subjectId, Recorded::detail)
                    .containsExactly(tuple(ada, AuditRefusalReason.ACCOUNT_LOCKED.name()));
            softly.assertThat(users.require("ada").login().failedLoginAttempts())
                    .as("leaves the run of the User who took the name alone")
                    .isEqualTo(2);
        });
    }

    /**
     * A User with no password is reported to Spring Security as no User at all (it has no
     * password Login), yet its refusal still names it, by the stable id the one read found.
     */
    @Test
    void aPasswordlessUsersRefusalIsHeldAgainstTheUserTheAuthenticationReadWhenAnotherTakesItsName() {
        UUID nopass = users.given(ScimIdentities.credentiallessUser("nopass")).id();
        users.afterNextNameLookup(() -> {
            users.rename(nopass, "nopass-renamed");
            users.given(withPassword("nopass", 2));
        });

        login.logIn("nopass", PASSWORD, "nopass-current");

        assertSoftly(softly -> {
            softly.assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                    .as("audited against the User the read found")
                    .extracting(Recorded::subjectId, Recorded::detail)
                    .containsExactly(tuple(nopass, AuditRefusalReason.BAD_CREDENTIALS.name()));
            softly.assertThat(users.require("nopass").login().failedLoginAttempts())
                    .as("leaves the run of the User who took the name alone")
                    .isEqualTo(2);
        });
    }

    /** A name the authentication found no User for is refused against nobody, whoever takes it. */
    @Test
    void anUnknownNameIsHeldAgainstNobodyWhenAUserTakesTheNameAfterTheRead() {
        users.afterNextNameLookup(() -> users.given(withPassword("grace", 2)));

        login.logIn("grace", PASSWORD, null);

        assertSoftly(softly -> {
            softly.assertThat(audit.of(AuditOperation.LOGIN_FAILURE))
                    .as("audited against nobody")
                    .extracting(Recorded::subjectId, Recorded::detail)
                    .containsExactly(tuple(null, AuditRefusalReason.UNKNOWN_ACCOUNT.name()));
            softly.assertThat(users.require("grace").login().failedLoginAttempts())
                    .as("leaves the run of the User who takes the name alone")
                    .isEqualTo(2);
        });
    }

    // ---- an authentication the decision cannot work from ------------------------------------

    /**
     * An authentication whose principal names its User only by {@code userName} gives the Login
     * nothing to work from but the name, which it refuses to look up again: the Login fails
     * closed, and records no success.
     */
    @Test
    void anAuthenticationCarryingNoStableIdFailsTheLoginClosed() {
        substitute = UsernamePasswordAuthenticationToken.authenticated(
                "ada", null, List.of());

        assertFailsClosedRecordingNoSuccess();
    }

    /**
     * An authentication that names its User by stable id but compared no stored credential — one
     * that did not come from the password provider — gives the decision under the lock nothing to
     * confirm, so the Login fails closed rather than skip the check, and records no success.
     */
    @Test
    void anAuthenticationThatComparedNoStoredCredentialFailsTheLoginClosed() {
        substitute = authenticatedWithoutComparing();

        assertFailsClosedRecordingNoSuccess();
    }

    private void assertFailsClosedRecordingNoSuccess() {
        assertSoftly(softly -> {
            softly.assertThatIllegalStateException()
                    .as("the Login fails closed")
                    .isThrownBy(() -> login.logIn("ada", PASSWORD, null));
            softly.assertThat(audit.of(AuditOperation.LOGIN_SUCCESS))
                    .as("no success is recorded")
                    .isEmpty();
        });
    }

    /** Ada's principal as the directory reports it, authenticated with no password compared. */
    private Authentication authenticatedWithoutComparing() {
        LoginPrincipal principal = harness.identities().loadUserByUsername("ada");
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
