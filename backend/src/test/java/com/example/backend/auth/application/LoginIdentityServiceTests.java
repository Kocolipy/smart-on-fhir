package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.authorization.domain.RoleMapping.GroupAssignment;
import com.example.backend.authorization.domain.RoleMapping.RoleDefinition;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * What the login path sees of the directory, now that a SCIM User is the only login
 * identity: the credential, the two refusal states, and the authority its Group membership
 * confers.
 *
 * <p>Replaces {@code AccountServiceTests}. Everything that class asserted about reporting a
 * stored identity to Spring Security is here, translated to the SCIM types — minus the
 * seeding assertions, which moved with the behaviour: seeding is
 * {@code ScimSeedService}/{@code ScimSeedConfig}'s now and is asserted there, because this
 * service reads and never writes.
 *
 * <p>What is new is the derivation. There is no role column, so administrative authority is
 * a question about membership of the RESERVED Admin group — and the tests below are arranged
 * so an implementation reading a display name, or recomputing per request, fails rather than
 * passes.
 */
class LoginIdentityServiceTests {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final CountingPasswordEncoder passwordEncoder = new CountingPasswordEncoder();

    private final LoginIdentityService service =
            new LoginIdentityService(users, groups, passwordEncoder, TestRoleMappings.superuserOnly());

    // Reporting the stored identity to Spring Security

    /**
     * A User with a required password change authenticates normally — the refusal is at
     * authorization, so the state is not enumerable before login — but receives the confined
     * authority ONLY: no {@code ROLE_USER}, and for a member of the Admin group no
     * {@code ROLE_ADMIN} either.
     */
    @Test
    void aUserRequiredToChangeItsPasswordReceivesOnlyTheConfinedAuthority() {
        ScimUser grace = users.given(ScimIdentities.userWithLoginState(
                "grace", new ScimLoginState("hash", 0, null, null, ScimIdentities.NOW)));
        groups.createReserved(
                ScimIdentities.group("Admins", grace), ReservedResourceName.ADMIN_GROUP);

        UserDetails details = service.loadUserByUsername("grace");

        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY);
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.getPassword()).isEqualTo("hash");
    }
    @Test
    void loadsThePersistedIdentityAsSpringSecurityUserDetails() {
        users.given(ScimIdentities.user("admin"));

        UserDetails details = service.loadUserByUsername("admin");

        assertThat(details.getUsername()).isEqualTo("admin");
        assertThat(details.getPassword()).isEqualTo("hash");
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isEnabled()).isTrue();
    }

    /**
     * Looked up on the normalized userName, which is what uniqueness is decided on, so a
     * correct password is not refused because of how the name was typed — and reported back
     * under the STORED spelling, which is Spring Security's own vocabulary and what the
     * login and {@code /me} responses carry.
     */
    @Test
    void looksUpOnTheNormalizedUserNameAndReportsTheStoredSpelling() {
        users.given(ScimIdentities.user("Ada"));

        assertThat(service.loadUserByUsername("ADA").getUsername()).isEqualTo("Ada");
    }

    /**
     * Carrying the lockout into {@code UserDetails} is what rejects a locked identity
     * before its password is compared, so the flag has to reflect the stored lock state
     * rather than only the attempt count.
     */
    @Test
    void reportsALockedIdentityAsLockedToSpringSecurity() {
        users.given(ScimIdentities.userWithLoginState(
                "ada", new ScimLoginState("hash", 3, Instant.parse("2026-09-24T07:00:00Z"))));

        assertThat(service.loadUserByUsername("ada").isAccountNonLocked()).isFalse();
    }

    /**
     * And keeps reporting it locked however long it has stood. There is no clock in this
     * decision at all — a lock is the recorded instant being present — so a lock imposed in
     * the distant past reads exactly the same as one imposed a moment ago.
     */
    @Test
    void keepsReportingALockedIdentityAsLockedHoweverOldTheLockIs() {
        users.given(ScimIdentities.userWithLoginState(
                "ada", new ScimLoginState("hash", 3, Instant.parse("1999-01-01T00:00:00Z"))));

        assertThat(service.loadUserByUsername("ada").isAccountNonLocked()).isFalse();
    }

    /**
     * The listing reports {@code active}, so authentication has to act on it — otherwise
     * the field is decoration and a deactivated identity still logs in. Like a lockout, no
     * passage of time lifts this.
     */
    @Test
    void reportsAnInactiveIdentityAsDisabled() {
        users.given(ScimIdentities.inactiveUser("retired"));

        UserDetails details = service.loadUserByUsername("retired");

        assertThat(details.isEnabled()).isFalse();
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void rejectsAnUnknownUsername() {
        assertThatThrownBy(() -> service.loadUserByUsername("missing"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    // Linking an Epic Login's Practitioner ID to a User

    @Test
    void anEpicPractitionerIdLinksToTheUserWhoseUserNameItIs() {
        users.given(ScimIdentities.user("eABC123"));

        assertThat(service.loadEpicLinkedUser("eABC123"))
                .map(UserDetails::getUsername)
                .contains("eABC123");
    }

    /**
     * Epic IDs are case-sensitive (D3) while the directory's lookup normalizes, so an ID that
     * differs from the stored {@code userName} only in case links to nobody.
     */
    @Test
    void anEpicPractitionerIdDifferingOnlyInCaseLinksToNoUser() {
        users.given(ScimIdentities.user("eABC123"));

        assertThat(service.loadEpicLinkedUser("eabc123")).isEmpty();
    }

    /** The Bootstrap Admin links to nothing whatever its name (D6): password Login recovers it. */
    @Test
    void anEpicPractitionerIdNamingTheBootstrapAdminLinksToNoUser() {
        users.createReserved(ScimIdentities.user("eRECOVERY"), ReservedResourceName.BOOTSTRAP_ADMIN);

        assertThat(service.loadEpicLinkedUser("eRECOVERY")).isEmpty();
    }

    /**
     * Only a password Login is confined by the change-required flag: an Epic Login presents no
     * password of ours (D20), so the imposed credential is not what it used. A flagged User signed
     * in through Epic holds {@code ROLE_USER}, the baseline Permissions and its Role mapping
     * Permissions, exactly as an unflagged one does.
     */
    @Test
    void anEpicLoginOfAUserWithTheChangeRequiredFlagHoldsTheAuthoritiesOfAnUnflaggedOne() {
        ScimUser grace = users.given(ScimIdentities.userWithLoginState(
                "eGRACE1", new ScimLoginState("hash", 0, null, null, ScimIdentities.NOW)));
        groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(grace.id())), ScimIdentities.NOW));

        assertThat(mapped.loadEpicLinkedUser("eGRACE1").map(LoginIdentityServiceTests::authoritiesOf))
                .contains(List.of("ROLE_USER", "counter:read", "counter:write", "user:read", "user:write"));
    }

    /** A flagged User the Epic path links to is still reported locked when it is locked. */
    @Test
    void anEpicLoginReportsAFlaggedLockedUserAsLocked() {
        users.given(ScimIdentities.userWithLoginState("eGRACE1", new ScimLoginState(
                "hash", 3, Instant.parse("2026-09-24T07:00:00Z"), null, ScimIdentities.NOW)));

        assertThat(service.loadEpicLinkedUser("eGRACE1").map(UserDetails::isAccountNonLocked))
                .contains(false);
    }

    /** And deactivated when it is deactivated: only the authorities ignore the flag. */
    @Test
    void anEpicLoginReportsAFlaggedInactiveUserAsDisabled() {
        ScimUser retired = users.given(ScimIdentities.inactiveUser("eRETIRED1"));
        users.requirePasswordChange(retired.id(), ScimIdentities.NOW);

        assertThat(service.loadEpicLinkedUser("eRETIRED1").map(UserDetails::isEnabled))
                .contains(false);
    }

    /** No Practitioner ID links to nobody, rather than failing the way normalizing one would. */
    @Test
    void aBlankOrMissingEpicPractitionerIdLinksToNoUser() {
        users.given(ScimIdentities.user("eABC123"));

        assertThat(Stream.of("   ", null).map(service::loadEpicLinkedUser))
                .containsOnly(Optional.empty());
    }

    /**
     * A blank submission cannot normalize, and it is refused as the unknown name it is
     * rather than as a different, distinguishable kind of failure — which would tell a
     * caller its input was rejected for a reason other than being wrong.
     */
    @Test
    void refusesABlankUsernameTheWayItRefusesAnUnknownOne() {
        assertThatThrownBy(() -> service.loadUserByUsername("   "))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    /**
     * A credentialless identity — no password hash ever set — must still produce
     * {@code UserDetails} with a non-null password: {@code User.withUsername} throws on
     * {@code null} before {@code DaoAuthenticationProvider} ever reaches the comparison,
     * which would refuse the login differently (and detectably) from a wrong-password
     * attempt on an identity that does have a hash. What the marker equals is not the point
     * — only that no submitted password matches it, so the identity is refused the same way
     * any other wrong password is.
     */
    @Test
    void reportsACredentiallessIdentityWithANonNullUnmatchablePassword() {
        users.given(ScimIdentities.credentiallessUser("nopass"));

        UserDetails details = service.loadUserByUsername("nopass");

        assertThat(details.getPassword()).isNotNull();
        assertThat(details.getPassword()).isNotEqualTo("anything the caller could submit");
    }

    /**
     * The marker is a hash the configured encoder produced, not an arbitrary string: only a
     * real encoded value makes {@code DaoAuthenticationProvider} run a full comparison, at the
     * cost of a genuine one, before refusing.
     */
    @Test
    void reportsACredentiallessIdentityWithAPasswordTheConfiguredEncoderProduced() {
        users.given(ScimIdentities.credentiallessUser("nopass"));

        String marker = service.loadUserByUsername("nopass").getPassword();

        assertThat(passwordEncoder.encoded()).containsExactly(marker);
    }

    /**
     * The unmatchable marker is encoded once per process and reused. Argon2id is
     * deliberately expensive, so recomputing it on every credentialless login attempt would
     * hand an unauthenticated caller a way to spend this service's CPU at will — the cache
     * is a cost control, not a tidiness measure.
     *
     * <p>Asserted by call count rather than by comparing markers: the encoder is
     * deterministic, so a marker recomputed on every call is byte-identical to a cached one
     * and no equality assertion can tell them apart.
     */
    @Test
    void theUnmatchableMarkerIsEncodedOnceAndReusedAcrossCalls() {
        users.given(ScimIdentities.credentiallessUser("nopass"));

        String first = service.loadUserByUsername("nopass").getPassword();
        String second = service.loadUserByUsername("nopass").getPassword();
        String third = service.loadUserByUsername("nopass").getPassword();

        assertThat(passwordEncoder.encodeCountOf("no-password-set")).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    /**
     * Two first lookups that race still encode the marker once: the second, arriving while the
     * first is mid-encode, waits for and reuses the first's marker rather than paying for its own
     * Argon2id run. Otherwise a burst of credentialless attempts against a cold process would
     * each spend one.
     */
    @Test
    void twoConcurrentFirstLookupsEncodeTheMarkerOnce() throws Exception {
        users.given(ScimIdentities.credentiallessUser("nopass"));
        GatedPasswordEncoder gatedEncoder = new GatedPasswordEncoder();
        LoginIdentityService gated = new LoginIdentityService(
                users, groups, gatedEncoder, TestRoleMappings.superuserOnly());
        FutureTask<UserDetails> first = new FutureTask<>(() -> gated.loadUserByUsername("nopass"));
        FutureTask<UserDetails> second = new FutureTask<>(() -> gated.loadUserByUsername("nopass"));
        new Thread(first).start();
        gatedEncoder.awaitFirstEncode();
        Thread secondThread = new Thread(second);
        secondThread.start();
        awaitBlocked(secondThread);

        gatedEncoder.release();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        assertThat(gatedEncoder.encodeCount()).isEqualTo(1);
    }

    /**
     * Two credentialless identities share the one marker, so the cache is keyed to the
     * process rather than recomputed per identity.
     */
    @Test
    void twoCredentiallessIdentitiesShareTheOneEncodedMarker() {
        users.given(ScimIdentities.credentiallessUser("nopass-one"));
        users.given(ScimIdentities.credentiallessUser("nopass-two"));

        String one = service.loadUserByUsername("nopass-one").getPassword();
        String two = service.loadUserByUsername("nopass-two").getPassword();

        assertThat(passwordEncoder.encodeCountOf("no-password-set")).isEqualTo(1);
        assertThat(two).isEqualTo(one);
    }

    /** A stored hash is never re-encoded: it is reported through as it stands. */
    @Test
    void reportingAnIdentityWithACredentialEncodesNothing() {
        users.given(ScimIdentities.user("ada"));

        service.loadUserByUsername("ada");

        assertThat(passwordEncoder.totalEncodeCalls()).isZero();
    }

    // Resolving the stable id

    /**
     * The stable id behind a userName, which the session index is keyed by. Spring Security
     * carries the userName, so without this the session index would be keyed by a mutable
     * value.
     */
    @Test
    void resolvesTheStableIdBehindAUserName() {
        ScimUser stored = users.given(ScimIdentities.user("user"));

        assertThat(service.resolveUserId("user")).isEqualTo(stored.id());
    }

    @Test
    void resolvesTheStableIdOnTheNormalizedUserName() {
        ScimUser stored = users.given(ScimIdentities.user("Ada"));

        assertThat(service.resolveUserId("ADA")).isEqualTo(stored.id());
    }

    /**
     * An unknown userName is refused rather than resolved to null. A null id would travel
     * into the session index as a key, silently indexing sessions under nothing instead of
     * failing where the mistake was made.
     */
    @Test
    void refusesToResolveAnIdForAnUnknownUserName() {
        assertThatThrownBy(() -> service.resolveUserId("missing"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    // Authority, which is derived from Group membership rather than stored

    /**
     * Baseline access is what being an active identity means, so an identity outside the
     * Admin group holds {@code ROLE_USER} and the baseline counter Permissions, and nothing else.
     */
    @Test
    void anIdentityOutsideTheAdminGroupHoldsUserAuthorityAlone() {
        users.given(ScimIdentities.user("ada"));
        givenAdminGroup();

        assertThat(authoritiesOf("ada")).containsExactly(BASELINE);
    }

    /**
     * There is no administrative role any more (ADR 0010): a member of the Admin group holds
     * baseline access and its Role's Permissions, never {@code ROLE_ADMIN}. The Admin group's id
     * here is not the mapping's Superuser Group, so it confers nothing at all.
     */
    @Test
    void anIdentityInTheAdminGroupHoldsNoAdministrativeRole() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        givenAdminGroup(ada);

        assertThat(authoritiesOf("ada")).containsExactly(BASELINE);
    }

    /** With no Admin group seeded at all, nobody is an administrator. */
    @Test
    void withNoAdminGroupSeededNobodyHoldsAdminAuthority() {
        users.given(ScimIdentities.user("ada"));

        assertThat(authoritiesOf("ada")).containsExactly(BASELINE);
    }

    /**
     * The Group is resolved through its reservation marker, never through its label. So a
     * Group that merely <em>displays</em> as the administrators' one confers nothing — which
     * is the difference between authority a connector cannot forge and authority anyone who
     * may create a Group can grant themselves.
     */
    @Test
    void membershipOfAGroupThatMerelyDisplaysAsTheAdminGroupConfersNothing() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        // The reserved Group is deliberately NOT the one named "Admins" here.
        groups.createReserved(
                ScimIdentities.group("Reserved administrators"),
                ReservedResourceName.ADMIN_GROUP);
        groups.create(ScimIdentities.group("Admins", ada));

        assertThat(authoritiesOf("ada")).containsExactly(BASELINE);
    }

    /** Membership is direct only, so another Group's members are not administrators. */
    @Test
    void membershipOfSomeOtherGroupConfersNoAdminAuthority() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        givenAdminGroup();
        groups.create(ScimIdentities.group("Engineering", ada));

        assertThat(authoritiesOf("ada")).containsExactly(BASELINE);
    }

    /**
     * Authority is read at login and never recomputed, and that is the specified behaviour
     * rather than a limitation: a session carries the authorities it was issued with, so
     * adding an identity to a mapped Group grants its Role's Permissions at its NEXT login
     * and never mid-session.
     *
     * <p>Both halves are needed. The already-issued principal proves nothing was
     * recomputed; the fresh load proves the grant did take effect, so the first half cannot
     * pass by the membership write having quietly failed.
     */
    @Test
    void authorityIsReadAtLoginAndNeverRecomputedForAnAlreadyIssuedPrincipal() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        ScimGroup helpdesk = groups.given(
                ScimGroup.created(HELPDESK, "Helpdesk", List.of(), ScimIdentities.NOW));

        UserDetails issuedBeforeTheGrant = mapped.loadUserByUsername("ada");
        assertThat(authoritiesOf(issuedBeforeTheGrant)).containsExactly(BASELINE);

        addMember(helpdesk, ada);

        assertThat(authoritiesOf(issuedBeforeTheGrant)).containsExactly(BASELINE);
        assertThat(authoritiesOf(mapped.loadUserByUsername("ada")))
                .containsExactly("ROLE_USER", "counter:read", "counter:write", "user:read", "user:write");
    }

    /** And the converse: a removal likewise takes effect at the next login, not before. */
    @Test
    void revokingMembershipTakesEffectAtTheNextLoginRatherThanMidSession() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        ScimGroup helpdesk = groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        UserDetails issuedWhileHelpdesk = mapped.loadUserByUsername("ada");
        assertThat(authoritiesOf(issuedWhileHelpdesk))
                .containsExactly("ROLE_USER", "counter:read", "counter:write", "user:read", "user:write");

        groups.replace(helpdesk.replacedWith(helpdesk.displayName(), List.of(), ScimIdentities.NOW))
                .orElseThrow();

        assertThat(authoritiesOf(issuedWhileHelpdesk))
                .containsExactly("ROLE_USER", "counter:read", "counter:write", "user:read", "user:write");
        assertThat(authoritiesOf(mapped.loadUserByUsername("ada"))).containsExactly(BASELINE);
    }

    /** An inactive member of a mapped Group is still reported with its Permissions; it is simply disabled. */
    @Test
    void anInactiveMemberIsStillReportedWithItsPermissions() {
        ScimUser ada = users.given(ScimIdentities.inactiveUser("ada"));
        groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        UserDetails details = mapped.loadUserByUsername("ada");

        assertThat(authoritiesOf(details)).containsExactly("ROLE_USER", "counter:read", "counter:write", "user:read", "user:write");
        assertThat(details.isEnabled()).isFalse();
    }

    // Permissions from the role mapping

    private static final UUID HELPDESK = UUID.fromString(
            "00000000-0000-4000-8000-0000000000b1");
    private static final UUID AUDITORS = UUID.fromString(
            "00000000-0000-4000-8000-0000000000b2");

    /** Two Roles and a Superuser, each conferred by its own Group. */
    private final LoginIdentityService mapped = new LoginIdentityService(
            users, groups, passwordEncoder, RoleMapping.of(
                    List.of(
                            new RoleDefinition("Superuser", TestRoleMappings.EVERY_PERMISSION),
                            new RoleDefinition("Helpdesk", List.of("user:write", "user:read")),
                            new RoleDefinition("Auditor", List.of("audit:read", "user:read"))),
                    List.of(
                            new GroupAssignment(
                                    TestRoleMappings.SUPERUSER_GROUP_ID, "Superuser", true),
                            new GroupAssignment(HELPDESK, "Helpdesk", false),
                            new GroupAssignment(AUDITORS, "Auditor", false))));

    /**
     * The union of the Roles of every mapped Group the User is directly in — each Permission once,
     * sorted by name, after the roles — and an unmapped Group adds nothing.
     */
    @Test
    void aUserInSeveralMappedGroupsHoldsTheUnionOfTheirPermissions() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));
        groups.given(ScimGroup.created(AUDITORS, "Auditors",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));
        groups.given(ScimIdentities.group("Unmapped", ada));

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada")))
                .containsExactly("ROLE_USER", "audit:read", "counter:read", "counter:write",
                        "user:read", "user:write");
    }

    /** One mapped Group: exactly its Role's Permissions, and another Role's are not leaked in. */
    @Test
    void aUserInOneMappedGroupHoldsExactlyThatRolesPermissions() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        groups.given(ScimGroup.created(AUDITORS, "Auditors",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada")))
                .containsExactly("ROLE_USER", "audit:read", "counter:read", "counter:write",
                        "user:read");
    }

    /**
     * No mapped Group: no Role's Permission, and baseline access all the same — which includes
     * the counter's Permissions, held by every active User whatever its Groups.
     */
    @Test
    void aUserInNoMappedGroupHoldsOnlyTheBaselinePermissions() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        groups.given(ScimIdentities.group("Unmapped", ada));

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada"))).containsExactly(BASELINE);
    }

    /** A confined session holds no Permission either, whatever Groups confer. */
    @Test
    void aUserRequiredToChangeItsPasswordHoldsNoPermission() {
        ScimUser grace = users.given(ScimIdentities.userWithLoginState(
                "grace", new ScimLoginState("hash", 0, null, null, ScimIdentities.NOW)));
        groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(grace.id())), ScimIdentities.NOW));

        assertThat(authoritiesOf(mapped.loadUserByUsername("grace")))
                .containsExactly(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY);
    }

    /** The Superuser Group confers every Permission, and no administrative role beside them. */
    @Test
    void theSuperuserGroupConfersEveryPermission() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        groups.createReserved(ScimGroup.created(TestRoleMappings.SUPERUSER_GROUP_ID, "Admins",
                        List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW),
                ReservedResourceName.ADMIN_GROUP);

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada")))
                .startsWith("ROLE_USER")
                .doesNotContain("ROLE_ADMIN")
                .hasSize(1 + TestRoleMappings.EVERY_PERMISSION.size())
                .containsAll(TestRoleMappings.EVERY_PERMISSION);
    }

    /** The hash the session records is the mapping's own. */
    @Test
    void reportsTheHashOfTheMappingItResolvesUnder() {
        RoleMapping mapping = TestRoleMappings.superuserOnly();

        assertThat(new LoginIdentityService(users, groups, passwordEncoder, mapping)
                        .roleMappingHash())
                .isEqualTo(mapping.hash());
    }

    /**
     * The reservation is applied through the port, because production has no other way to
     * produce one.
     */
    private ScimGroup givenAdminGroup(ScimUser... members) {
        return groups.createReserved(
                ScimIdentities.group("Admins", members), ReservedResourceName.ADMIN_GROUP);
    }

    private ScimGroup addMember(ScimGroup group, ScimUser user) {
        return groups.replace(group.replacedWith(
                        group.displayName(),
                        Stream.concat(
                                        group.members().stream(),
                                        Stream.of(ScimGroupMember.reference(user.id())))
                                .toList(),
                        ScimIdentities.NOW))
                .orElseThrow();
    }

    /**
     * What every active User holds with no mapped Group: {@code ROLE_USER} and the counter's two
     * Permissions, sorted by name as Spring Security's {@code User} keeps them.
     */
    private static final String[] BASELINE = {"ROLE_USER", "counter:read", "counter:write"};

    private List<String> authoritiesOf(String userName) {
        return authoritiesOf(service.loadUserByUsername(userName));
    }

    private static List<String> authoritiesOf(UserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /**
     * Counts what it was asked to encode, per raw value.
     *
     * <p>Deterministic on purpose, and that is exactly why the count is what the assertions
     * read: a re-encode returns an identical string, so no equality assertion could tell a
     * cached marker from one recomputed on every call.
     */
    private static final class CountingPasswordEncoder implements PasswordEncoder {

        private final Map<String, Integer> encodeCounts = new HashMap<>();

        private final List<String> encoded = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            encodeCounts.merge(rawPassword.toString(), 1, Integer::sum);
            String hash = "encoded:" + rawPassword;
            encoded.add(hash);
            return hash;
        }

        /** Every value {@link #encode} returned, in order. */
        List<String> encoded() {
            return List.copyOf(encoded);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encode(rawPassword).equals(encodedPassword);
        }

        int encodeCountOf(String rawPassword) {
            return encodeCounts.getOrDefault(rawPassword, 0);
        }

        int totalEncodeCalls() {
            return encodeCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** Waits, up to five seconds, until {@code thread} is blocked waiting to enter a monitor. */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertThat(System.nanoTime()).as("the second lookup is waiting").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    /**
     * Counts its encodes, and holds the first one open until {@link #release} so a test can
     * start a second caller while the first is mid-encode.
     */
    private static final class GatedPasswordEncoder implements PasswordEncoder {

        private final AtomicInteger encodes = new AtomicInteger();

        private final CountDownLatch firstEncodeStarted = new CountDownLatch(1);

        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public String encode(CharSequence rawPassword) {
            encodes.incrementAndGet();
            firstEncodeStarted.countDown();
            try {
                assertThat(released.await(5, TimeUnit.SECONDS)).as("released").isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return "encoded:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encode(rawPassword).equals(encodedPassword);
        }

        void awaitFirstEncode() throws InterruptedException {
            assertThat(firstEncodeStarted.await(5, TimeUnit.SECONDS)).as("first encode").isTrue();
        }

        void release() {
            released.countDown();
        }

        int encodeCount() {
            return encodes.get();
        }
    }
}
