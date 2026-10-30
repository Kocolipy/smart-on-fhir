package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.LoginHarness;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginService.LoginDecision;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;

/**
 * A password Login is decided once, under the User's lock, on the state stored there: the
 * credential the authentication verified must still be the stored one, and whether the session is
 * confined is read from the same state that decides the dormancy basis.
 *
 * <p>The authentication reads the User by name, then compares the password — an Argon2id
 * verification long enough for a connector's write to commit in between. The in-memory store's
 * {@link InMemoryScimUserRepository#afterNextNameLookup} lands that write right after the read,
 * before the comparison and the lock: the window a concurrent SCIM write lands in. The decision
 * is the one {@link LoginHarness} assembles, at its default lockout threshold.
 */
class PasswordLoginUnderLockTests {

    private static final Instant NOW = LoginHarness.NOW;

    private static final Instant EARLIER = Instant.parse("2026-06-01T00:00:00Z");

    private static final String PASSWORD = "correct-password";

    private static final int LIMIT = LoginHarness.DEFAULT_LOCKOUT_THRESHOLD;

    private final LoginHarness harness = LoginHarness.create();
    private final InMemoryScimUserRepository users = harness.users();
    private final InMemoryAccountSessions sessions = harness.sessions();
    private final RecordingAuditTrail audit = harness.audit();
    private final LoginService login = harness.login();

    private final String hash = harness.passwordEncoder().encode(PASSWORD);

    private final UUID ada =
            users.given(adaWith(new ScimLoginState(hash, 0, null, null, EARLIER, null))).id();

    // ---- the credential, confirmed under the lock --------------------------------------------

    /** A credential written while the old one was being compared, by whoever wrote it. */
    enum CredentialWrite {
        /** As a connector's PUT stores a password: a new credential, and a change required. */
        A_CONNECTOR_REPLACES_THE_PASSWORD,
        /** As Ada's own completed change stores one: a new credential, and no change owed. */
        ADA_CHANGES_HER_PASSWORD_ELSEWHERE,
        /** The same password written again: the hash is salted per write, so a new credential. */
        THE_PASSWORD_IS_REWRITTEN_TO_THE_SAME_VALUE
    }

    /**
     * At the moment of the decision the submitted password is simply wrong, so the Login is
     * refused, recorded and counted exactly as a rejected password is, and moves nothing a
     * success would. Ada's own change leaves no change required, so for it only the credential
     * check stands between the old password and a Login that would move the dormancy basis.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(CredentialWrite.class)
    void aLoginWhoseCredentialIsReplacedDuringTheComparisonIsRefusedAsAWrongPasswordIs(
            CredentialWrite write) {
        sessions.open(ada, "ada-current");
        sessions.open(ada, "ada-elsewhere");
        users.afterNextNameLookup(() -> users.given(adaWith(written(write))));

        LoginDecision decision = login.logIn("ada", PASSWORD, "ada-current");
        harness.transaction().commit();

        assertSoftly(softly -> {
            softly.assertThat(decision.outcome())
                    .as("refused as bad credentials")
                    .isEqualTo(new PasswordRefused(ada, PasswordRefusalReason.BAD_CREDENTIALS));
            softly.assertThat(decision.accepted()).as("issues no session").isEmpty();
            softly.assertThat(audit.recorded())
                    .as("audited as one failure naming the User")
                    .extracting(Recorded::operation, Recorded::subjectId, Recorded::detail)
                    .containsExactly(tuple(AuditOperation.LOGIN_FAILURE, ada,
                            AuditRefusalReason.BAD_CREDENTIALS.name()));
            softly.assertThat(users.require("ada").login().failedLoginAttempts())
                    .as("counted on the failure run")
                    .isEqualTo(1);
            softly.assertThat(users.require("ada").login().lastAuthenticatedAt())
                    .as("leaves the dormancy basis")
                    .isEqualTo(EARLIER);
            softly.assertThat(sessions.sessionsOf(ada))
                    .as("ends no other session")
                    .containsExactlyInAnyOrder("ada-current", "ada-elsewhere");
        });
    }

    /** Counted exactly as a rejected password is: the one reaching the limit imposes a Lockout. */
    @Test
    void aLoginWhosePasswordIsReplacedDuringTheComparisonLocksTheUserAtTheLimit() {
        users.given(adaWith(new ScimLoginState(hash, LIMIT - 1, null, null, EARLIER, null)));
        users.afterNextNameLookup(
                () -> users.given(adaWith(written(CredentialWrite.A_CONNECTOR_REPLACES_THE_PASSWORD))));

        login.logIn("ada", PASSWORD, "ada-current");

        assertThat(audit.of(AuditOperation.LOCKOUT_SET))
                .extracting(Recorded::subjectId)
                .containsExactly(ada);
    }

    /**
     * With nothing written in between, the same Login is accepted, its session unconfined: the
     * check refuses no one else.
     */
    @Test
    void aLoginWhosePasswordStandsIsAcceptedUnconfined() {
        LoginDecision decision = login.logIn("ada", PASSWORD, "ada-current");

        assertSoftly(softly -> {
            softly.assertThat(decision.accepted()).as("accepted").isPresent();
            decision.accepted().ifPresent(accepted -> softly
                    .assertThat(accepted.authentication().getAuthorities())
                    .as("issued unconfined")
                    .extracting(GrantedAuthority::getAuthority)
                    .doesNotContain(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY)
                    .contains("ROLE_USER"));
        });
    }

    // ---- a User deleted while its Login is in flight -----------------------------------------

    /**
     * A SCIM {@code DELETE} committed while the password was being compared is a refusal under the
     * lock that arrived early, not a fault: the Login is refused, recorded as one subject-less
     * {@code LOGIN_FAILURE} — the User is nobody by then — and nothing a success does is done.
     */
    @Test
    void aLoginWhoseUserIsDeletedDuringTheComparisonIsRefusedAndRecordedAgainstNobody() {
        users.afterNextNameLookup(() -> users.deleteById(ada, NOW));

        LoginDecision decision = login.logIn("ada", PASSWORD, "ada-current");

        assertSoftly(softly -> {
            softly.assertThat(decision.outcome()).as("refused").isInstanceOf(PasswordRefused.class);
            softly.assertThat(decision.accepted()).as("issues no session").isEmpty();
            softly.assertThat(audit.recorded())
                    .as("one failure against nobody, and no success")
                    .extracting(Recorded::operation, Recorded::subjectId, Recorded::detail)
                    .containsExactly(tuple(AuditOperation.LOGIN_FAILURE, null,
                            AuditRefusalReason.UNKNOWN_ACCOUNT.name()));
            softly.assertThat(audit.loginMethods())
                    .as("under method password")
                    .containsExactly(AuditLoginMethod.PASSWORD);
            softly.assertThat(harness.transaction().pending())
                    .as("schedules no revocation")
                    .isZero();
        });
    }

    /** The two routes a password Login is counted by, which a deletion can overtake alike. */
    enum Submitted {
        /** The authentication accepted the password; the decision under the lock is the Login's. */
        THE_RIGHT_PASSWORD(PASSWORD),
        /** The authentication refused it; its refusal is counted under the lock. */
        A_WRONG_PASSWORD("wrong-password");

        private final String password;

        Submitted(String password) {
            this.password = password;
        }
    }

    /**
     * A SCIM {@code DELETE} committed after the Login's decision step read the User and just
     * before its lock was granted — one failure short of the limit, so a refusal counted on the
     * copy that read left would impose a Lockout on nobody. The User is gone under the lock: the
     * Login is one subject-less {@code LOGIN_FAILURE}, and no {@code LOCKOUT_SET} is recorded.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Submitted.class)
    void aLoginWhoseUserIsDeletedJustBeforeItsLockIsRecordedAgainstNobody(Submitted submitted) {
        users.given(adaWith(new ScimLoginState(hash, LIMIT - 1, null, null, EARLIER, null)));
        harness.locks().beforeNextGrant(() -> users.deleteById(ada, NOW));

        login.logIn("ada", submitted.password, "ada-current");

        assertThat(audit.recorded())
                .extracting(Recorded::operation, Recorded::subjectId, Recorded::detail)
                .containsExactly(tuple(AuditOperation.LOGIN_FAILURE, null,
                        AuditRefusalReason.UNKNOWN_ACCOUNT.name()));
    }

    /** ... and nothing is revoked for the User nobody is any more. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Submitted.class)
    void aLoginWhoseUserIsDeletedJustBeforeItsLockSchedulesNoRevocation(Submitted submitted) {
        users.given(adaWith(new ScimLoginState(hash, LIMIT - 1, null, null, EARLIER, null)));
        harness.locks().beforeNextGrant(() -> users.deleteById(ada, NOW));

        login.logIn("ada", submitted.password, "ada-current");

        assertThat(harness.transaction().pending()).isZero();
    }

    // ---- confinement, from the state the decision read ---------------------------------------

    /**
     * A change required since the read — with the same credential — confines the session: the
     * authorities come from the state under the lock, the one that left the dormancy basis
     * unmoved, not from the read before it. The confined principal the session carries holds
     * what the authentication does, no more.
     */
    @Test
    void aChangeRequiredSinceTheReadConfinesTheIssuedSessionAndPrincipal() {
        users.afterNextNameLookup(() -> users.given(adaWith(
                new ScimLoginState(hash, 0, null, null, EARLIER, NOW))));

        LoginService.AcceptedLogin accepted =
                login.logIn("ada", PASSWORD, "ada-current").accepted().orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(accepted.authentication().getAuthorities())
                    .as("the session's authorities")
                    .extracting(GrantedAuthority::getAuthority)
                    .contains(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY)
                    .doesNotContain("ROLE_USER", "patient:read");
            softly.assertThat(((LoginPrincipal) accepted.authentication().getPrincipal())
                            .getAuthorities())
                    .as("the principal's authorities")
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY);
        });
    }

    // ---- authority, from the state the decision read -----------------------------------------

    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    /**
     * A write that moves Ada's power while her Login is in flight — after the
     * authentication's read and its comparison, before the decision under her lock — and what her session holds
     * once that write has committed: the authority read under the lock, never the read's.
     */
    enum PowerWrite {
        /** A SCIM write removes Ada from the Helpdesk Group, which carries Permissions. */
        ADA_LEAVES_A_PERMISSION_CARRYING_GROUP("ROLE_USER", "patient:read"),
        /** A SCIM {@code DELETE} of the Helpdesk Group. */
        THE_PERMISSION_CARRYING_GROUP_IS_DELETED("ROLE_USER", "patient:read"),
        /** A Superuser's Group Permissions change takes {@code account:unlock} off the Group. */
        THE_GROUP_LOSES_A_PERMISSION("ROLE_USER", "patient:read", "user:read"),
        /** A SCIM write removes Ada from the Admin group: no Superuser, and no expansion. */
        ADA_LEAVES_THE_ADMIN_GROUP("ROLE_USER", "patient:read"),
        /** Gaining is the state under the lock too, which the session may reflect at once. */
        THE_GROUP_GAINS_A_PERMISSION(
                "ROLE_USER", "account:unlock", "audit:read", "patient:read", "user:read");

        private final List<String> issued;

        PowerWrite(String... issued) {
            this.issued = List.of(issued);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PowerWrite.class)
    void aSessionIsIssuedTheAuthorityTheUserHoldsUnderTheLock(PowerWrite write) {
        harness.locks().beforeNextGrant(arranged(write));

        LoginService.AcceptedLogin accepted =
                login.logIn("ada", PASSWORD, "ada-current").accepted().orElseThrow();

        assertThat(heldBesideTheFactor(accepted.authentication().getAuthorities()))
                .containsExactlyInAnyOrderElementsOf(write.issued);
    }

    /** The principal the session carries holds what its token does: one authority, not two. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(PowerWrite.class)
    void aSessionsPrincipalIsIssuedTheAuthorityTheUserHoldsUnderTheLock(PowerWrite write) {
        harness.locks().beforeNextGrant(arranged(write));

        LoginService.AcceptedLogin accepted =
                login.logIn("ada", PASSWORD, "ada-current").accepted().orElseThrow();

        assertThat(heldBesideTheFactor(
                        ((LoginPrincipal) accepted.authentication().getPrincipal()).getAuthorities()))
                .containsExactlyInAnyOrderElementsOf(write.issued);
    }

    /**
     * Arranges Ada's power as it stands when the authentication reads her, and answers the write
     * that moves it during the comparison.
     */
    private Runnable arranged(PowerWrite write) {
        InMemoryScimGroupRepository groups = harness.groups();
        InMemoryGroupPermissions permissions = harness.groupPermissions();
        return switch (write) {
            case ADA_LEAVES_A_PERMISSION_CARRYING_GROUP -> {
                adaInTheHelpdesk();
                yield () -> groups.removeMember(HELPDESK, ada, NOW);
            }
            case THE_PERMISSION_CARRYING_GROUP_IS_DELETED -> {
                adaInTheHelpdesk();
                yield () -> groups.deleteById(HELPDESK, NOW);
            }
            case THE_GROUP_LOSES_A_PERMISSION -> {
                adaInTheHelpdesk();
                yield () -> permissions.replace(HELPDESK, Set.of(Permission.USER_READ));
            }
            case THE_GROUP_GAINS_A_PERMISSION -> {
                adaInTheHelpdesk();
                yield () -> permissions.replace(HELPDESK, Set.of(
                        Permission.ACCOUNT_UNLOCK, Permission.USER_READ, Permission.AUDIT_READ));
            }
            case ADA_LEAVES_THE_ADMIN_GROUP -> {
                groups.given(ScimGroup.created(TestGroupPermissions.SUPERUSER_GROUP_ID, "Admins",
                        List.of(ScimGroupMember.reference(ada)), ScimIdentities.NOW));
                yield () -> groups.removeMember(
                        TestGroupPermissions.SUPERUSER_GROUP_ID, ada, NOW);
            }
        };
    }

    /** Ada in the Helpdesk Group, which carries {@code account:unlock} and {@code user:read}. */
    private void adaInTheHelpdesk() {
        harness.groups().given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(ada)), ScimIdentities.NOW));
        harness.groupPermissions()
                .carrying(HELPDESK, Permission.ACCOUNT_UNLOCK, Permission.USER_READ);
    }

    /** What a session holds, less the factor Spring Security records beside the authorities. */
    private static List<String> heldBesideTheFactor(
            Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .filter(authority -> !(authority instanceof FactorGrantedAuthority))
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    /** The login state {@code write} stores for Ada. */
    private ScimLoginState written(CredentialWrite write) {
        return switch (write) {
            case A_CONNECTOR_REPLACES_THE_PASSWORD -> new ScimLoginState(
                    harness.passwordEncoder().encode("connector-set-password"),
                    users.require("ada").login().failedLoginAttempts(), null, null, EARLIER, NOW);
            case ADA_CHANGES_HER_PASSWORD_ELSEWHERE -> new ScimLoginState(
                    harness.passwordEncoder().encode("adas-new-password"), 0, null, null, EARLIER,
                    null);
            case THE_PASSWORD_IS_REWRITTEN_TO_THE_SAME_VALUE -> new ScimLoginState(
                    harness.passwordEncoder().encode(PASSWORD), 0, null, null, EARLIER, null);
        };
    }

    private ScimUser adaWith(ScimLoginState login) {
        return new ScimUser(ada != null ? ada : UUID.randomUUID(),
                ScimIdentities.profile("ada", true), login, null, ScimUser.INITIAL_VERSION,
                ScimIdentities.NOW, ScimIdentities.NOW);
    }
}
