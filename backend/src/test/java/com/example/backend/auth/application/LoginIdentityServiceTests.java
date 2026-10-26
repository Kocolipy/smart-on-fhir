package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryGroupPermissions;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

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

    private final LoginIdentityService service =
            new LoginIdentityService(users, groups, InMemoryGroupPermissions.over(groups));

    // Reporting the stored identity to Spring Security

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
     * in through Epic holds {@code ROLE_USER}, the baseline Permissions and what its Groups
     * carry, exactly as an unflagged one does.
     */
    @Test
    void anEpicLoginOfAUserWithTheChangeRequiredFlagHoldsTheAuthoritiesOfAnUnflaggedOne() {
        ScimUser grace = users.given(ScimIdentities.userWithLoginState(
                "eGRACE1", new ScimLoginState("hash", 0, null, null, ScimIdentities.NOW)));
        groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(grace.id())), ScimIdentities.NOW));

        assertThat(mapped.loadEpicLinkedUser("eGRACE1").map(LoginIdentityServiceTests::authoritiesOf))
                .contains(List.of(HELPDESK_AUTHORITIES));
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
        ScimIdentities.storePasswordChangeRequired(users, retired.id(), ScimIdentities.NOW);

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
     * Blank without whitespace at an edge: an empty name has no edge at all, and U+001F is a
     * control Java counts as blank and Unicode does not count as whitespace. Each is the unknown
     * name it is, not a failure of normalization.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "\u001F"})
    void refusesABlankUsernameWithNoEdgeWhitespaceTheWayItRefusesAnUnknownOne(String username) {
        assertThatThrownBy(() -> service.loadUserByUsername(username))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    /** A missing name is the unknown name it is too, not a failure of normalization. */
    @Test
    void refusesAMissingUsernameTheWayItRefusesAnUnknownOne() {
        assertThatThrownBy(() -> service.loadUserByUsername(null))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    /**
     * A name ending in whitespace names nobody, even on a store that ignores trailing spaces as
     * SQL Server does and so would find {@code ada} for {@code ada } if it were asked: the refusal
     * is decided before the lookup, not by it.
     */
    @Test
    void refusesANameEndingInWhitespaceEvenWhereTheStoreWouldFindTheUserItPadsOnto() {
        users.ignoreTrailingSpacesInNameLookupsAsSqlServerDoes();
        users.given(ScimIdentities.user("ada"));

        assertThatThrownBy(() -> service.loadUserByUsername("ada "))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    /**
     * A credentialless identity — no password hash ever set — has no password Login, so it is
     * reported as no User at all: {@code DaoAuthenticationProvider} refuses it as it refuses an
     * unknown name, whatever was submitted, rather than comparing against a stand-in hash that
     * some submitted string could match.
     */
    @Test
    void refusesACredentiallessIdentityTheWayItRefusesAnUnknownOne() {
        users.given(ScimIdentities.credentiallessUser("nopass"));

        assertThatThrownBy(() -> service.loadUserByUsername("nopass"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    /** It still has an Epic Login: Epic checked the credential, not this service (D20). */
    @Test
    void anEpicPractitionerIdLinksToACredentiallessUser() {
        users.given(ScimIdentities.credentiallessUser("eNOPASS1"));

        assertThat(service.loadEpicLinkedUser("eNOPASS1"))
                .map(UserDetails::getUsername)
                .contains("eNOPASS1");
    }

    // Resolving the stable id

    /**
     * The User Spring Security authenticates carries its stable id, so a Login works from the
     * one read it made rather than looking the name up again.
     */
    @Test
    void theAuthenticatedPrincipalCarriesTheUsersStableId() {
        ScimUser stored = users.given(ScimIdentities.user("Ada"));

        assertThat(service.loadUserByUsername("ADA").userId()).isEqualTo(stored.id());
    }

    @Test
    void theEpicLinkedPrincipalCarriesTheUsersStableId() {
        ScimUser stored = users.given(ScimIdentities.user("eADA"));

        assertThat(service.loadEpicLinkedUser("eADA").orElseThrow().userId())
                .isEqualTo(stored.id());
    }

    /**
     * The stable id behind a userName, for a caller holding only the name Spring Security carries
     * that keys data of its own by the User. A Login does not need it: its principal carries the
     * id.
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

    /**
     * Authority is read at login and never recomputed, and that is the specified behaviour
     * rather than a limitation: a session carries the authorities it was issued with, so
     * adding an identity to a Group grants what the Group carries at its NEXT login
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
                .containsExactly(HELPDESK_AUTHORITIES);
    }

    /** And the converse: a removal likewise takes effect at the next login, not before. */
    @Test
    void revokingMembershipTakesEffectAtTheNextLoginRatherThanMidSession() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        ScimGroup helpdesk = groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        UserDetails issuedWhileHelpdesk = mapped.loadUserByUsername("ada");
        assertThat(authoritiesOf(issuedWhileHelpdesk))
                .containsExactly(HELPDESK_AUTHORITIES);

        groups.replace(helpdesk.replacedWith(helpdesk.displayName(), List.of(), ScimIdentities.NOW))
                .orElseThrow();

        assertThat(authoritiesOf(issuedWhileHelpdesk))
                .containsExactly(HELPDESK_AUTHORITIES);
        assertThat(authoritiesOf(mapped.loadUserByUsername("ada"))).containsExactly(BASELINE);
    }

    /** An inactive member of a Group is still reported with its Permissions; it is simply disabled. */
    @Test
    void anInactiveMemberIsStillReportedWithItsPermissions() {
        ScimUser ada = users.given(ScimIdentities.inactiveUser("ada"));
        groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        UserDetails details = mapped.loadUserByUsername("ada");

        assertThat(authoritiesOf(details)).containsExactly(HELPDESK_AUTHORITIES);
        assertThat(details.isEnabled()).isFalse();
    }

    // Permissions from the Groups (ADR 0015)

    /**
     * Baseline access is what being an active identity means: a User whose Groups confer nothing
     * holds {@code ROLE_USER} and the baseline Permissions, and nothing else — however it came to
     * hold no more.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "in no Group beside an Admin group",
            "with no Admin group seeded",
            "in a Group carrying nothing",
            "outside the only Group carrying Permissions"})
    void aUserWhoseGroupsConferNothingHoldsOnlyTheBaseline(String arrangement) {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        switch (arrangement) {
            case "in no Group beside an Admin group" -> givenAdminGroup();
            case "with no Admin group seeded" -> { }
            case "in a Group carrying nothing" -> groups.given(ScimIdentities.group("Carries nothing", ada));
            case "outside the only Group carrying Permissions" -> groups.given(ScimGroup.created(AUDITORS,
                    "Auditors", List.of(ScimGroupMember.reference(users.given(ScimIdentities.user("grace")).id())),
                    ScimIdentities.NOW));
            default -> throw new IllegalArgumentException(arrangement);
        }

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada"))).containsExactly(BASELINE);
    }

    /**
     * A session the login decision found confined receives the confined authority ONLY: no
     * {@code ROLE_USER}, no Permission, and in the Admin group not the Superuser either, whatever
     * its Groups carry. Whether it is confined is the decision's, under the User's lock
     * ({@code PasswordLoginUnderLockTests}); this is what the session is issued with once it is.
     */
    @ParameterizedTest(name = "a member of {0}")
    @ValueSource(strings = {"the Admin group", "a Group carrying Permissions"})
    void aConfinedSessionReceivesOnlyTheConfinedAuthority(String group) {
        ScimUser grace = users.given(ScimIdentities.userWithLoginState(
                "grace", new ScimLoginState("hash", 0, null, null, ScimIdentities.NOW)));
        if (group.equals("the Admin group")) {
            givenAdminGroup(grace);
        } else {
            groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                    List.of(ScimGroupMember.reference(grace.id())), ScimIdentities.NOW));
        }

        assertThat(mapped.issued(authenticated(mapped.loadUserByUsername("grace")), true)
                        .getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY);
    }

    /** The confined session's principal holds what its token does, and nothing of the read's. */
    @Test
    void aConfinedSessionsPrincipalHoldsOnlyTheConfinedAuthority() {
        ScimUser grace = users.given(ScimIdentities.user("grace"));
        givenAdminGroup(grace);

        assertThat(authoritiesOf((UserDetails) mapped.issued(
                        authenticated(mapped.loadUserByUsername("grace")), true).getPrincipal()))
                .containsExactly(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY);
    }

    /** The confined session's principal still names its User by the stable id the read found. */
    @Test
    void aConfinedSessionsPrincipalCarriesTheUsersStableId() {
        ScimUser grace = users.given(ScimIdentities.user("grace"));

        assertThat(((LoginPrincipal) mapped.issued(
                        authenticated(mapped.loadUserByUsername("grace")), true).getPrincipal())
                .userId())
                .isEqualTo(grace.id());
    }

    /** The confined session carries no credential, as an unconfined one does not. */
    @Test
    void aConfinedSessionCarriesNoCredential() {
        users.given(ScimIdentities.user("grace"));

        Authentication issued =
                mapped.issued(authenticated(mapped.loadUserByUsername("grace")), true);

        assertThat(List.of(Optional.ofNullable(issued.getCredentials()),
                        Optional.ofNullable(((UserDetails) issued.getPrincipal()).getPassword())))
                .containsOnly(Optional.empty());
    }

    /**
     * The authentication factor Spring Security records beside the authorities says how the User
     * authenticated, not what it may do, so a confined session keeps it.
     */
    @Test
    void aConfinedSessionKeepsTheAuthenticationFactor() {
        users.given(ScimIdentities.user("grace"));
        LoginPrincipal read = mapped.loadUserByUsername("grace");
        List<GrantedAuthority> withFactor = new ArrayList<>(read.getAuthorities());
        withFactor.add(
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY));

        assertThat(mapped.issued(UsernamePasswordAuthenticationToken.authenticated(
                        read, null, withFactor), true).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY,
                        FactorGrantedAuthority.PASSWORD_AUTHORITY);
    }

    /** An unconfined session is issued with the authentication exactly as it was verified. */
    @Test
    void anUnconfinedSessionIsIssuedAsAuthenticated() {
        users.given(ScimIdentities.user("grace"));
        Authentication verified = authenticated(mapped.loadUserByUsername("grace"));

        assertThat(mapped.issued(verified, false)).isSameAs(verified);
    }

    /** As the provider authenticates {@code read}: its authorities, its credential erased. */
    private static Authentication authenticated(LoginPrincipal read) {
        read.eraseCredentials();
        return UsernamePasswordAuthenticationToken.authenticated(
                read, null, read.getAuthorities());
    }

    private static final UUID HELPDESK = UUID.fromString(
            "00000000-0000-4000-8000-0000000000b1");
    private static final UUID AUDITORS = UUID.fromString(
            "00000000-0000-4000-8000-0000000000b2");

    /**
     * Two Groups carrying Permissions, as their stored rows say, over the in-memory directory: the
     * Admin group is the one holding the reservation marker, and carries none.
     */
    private final LoginIdentityService mapped = new LoginIdentityService(
            users, groups, InMemoryGroupPermissions.over(groups)
                    .carrying(HELPDESK, Permission.ACCOUNT_UNLOCK, Permission.USER_READ)
                    .carrying(AUDITORS, Permission.AUDIT_READ, Permission.USER_READ));

    /**
     * The union of what every Group the User is directly in carries — each Permission once,
     * sorted by name, after the roles — and a Group carrying nothing adds nothing.
     */
    @Test
    void aUserInSeveralGroupsHoldsTheUnionOfTheirPermissions() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        groups.given(ScimGroup.created(HELPDESK, "Helpdesk",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));
        groups.given(ScimGroup.created(AUDITORS, "Auditors",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));
        groups.given(ScimIdentities.group("Carries nothing", ada));

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada")))
                .containsExactly("ROLE_USER", "account:unlock", "audit:read", "patient:read",
                        "user:read");
    }

    /** One Group: exactly what it carries, and another Group's Permissions are not leaked in. */
    @Test
    void aUserInOneGroupHoldsExactlyWhatItCarries() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        groups.given(ScimGroup.created(AUDITORS, "Auditors",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada")))
                .containsExactly("ROLE_USER", "audit:read", "patient:read", "user:read");
    }

    /**
     * A direct member of the Admin group is the Superuser: {@code ROLE_SUPERUSER} beside baseline
     * access and every Permission a session may hold — though the Admin group carries no
     * Permission at all: the expansion is in code. The Admin group is the
     * Group holding the reservation marker, whatever its id — here not the id the seed uses
     * (ADR 0015).
     */
    @Test
    void aDirectMemberOfTheAdminGroupHoldsRoleSuperuserAndEveryPermission() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        givenAdminGroup(ada);

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada")))
                .contains("ROLE_USER", "ROLE_SUPERUSER")
                .hasSize(2 + TestGroupPermissions.EVERY_PERMISSION.size())
                .containsAll(TestGroupPermissions.EVERY_PERMISSION);
    }

    /**
     * The helpdesk Permission comes with the Superuser like every delegatable one, from membership
     * alone; the token-only write halves never do — no session holds them (ADR 0015).
     */
    @Test
    void theSuperuserHoldsAccountUnlockAndNoTokenOnlyWrite() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        givenSuperuserGroup(ada);

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada")))
                .contains("account:unlock")
                .doesNotContain("user:write", "group:write");
    }

    /**
     * The Admin group is recognised by its reservation marker alone: a Group under the id the
     * seed uses that does not hold the marker is an ordinary Group, conferring what it carries.
     */
    @Test
    void aGroupUnderTheAdminGroupsIdWithoutTheMarkerConfersNoSuperuser() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        groups.given(ScimGroup.created(TestGroupPermissions.SUPERUSER_GROUP_ID, "Impostors",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada"))).containsExactly(BASELINE);
    }

    /**
     * Superuser is not any Permission, and no number of Permissions makes one: a User whose Group
     * carries every delegatable Permission, not in the Admin group, holds no
     * {@code ROLE_SUPERUSER}.
     */
    @Test
    void holdingEveryDelegatablePermissionThroughAGroupDoesNotMakeAUserASuperuser() {
        UUID everything = UUID.fromString("00000000-0000-4000-8000-0000000000b3");
        LoginIdentityService delegating = new LoginIdentityService(
                users, groups, InMemoryGroupPermissions.over(groups)
                        .carrying(everything,
                                Permission.allDelegatable().toArray(Permission[]::new)));
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        groups.given(ScimGroup.created(everything, "Delegated",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        assertThat(authoritiesOf(delegating.loadUserByUsername("ada")))
                .containsExactly("ROLE_USER", "account:unlock", "audit:read", "connector:read",
                        "group:read", "ops:read", "patient:read", "user:read");
    }

    /** Membership is direct only: a member of some other Group is not a Superuser. */
    @Test
    void aMemberOfAnotherGroupIsNotASuperuser() {
        ScimUser ada = users.given(ScimIdentities.user("ada"));
        givenSuperuserGroup();
        groups.given(ScimIdentities.group("Engineering", ada));

        assertThat(authoritiesOf(mapped.loadUserByUsername("ada"))).containsExactly(BASELINE);
    }

    /** An Epic Login resolves the same authorities: a Superuser linked by Practitioner ID is one. */
    @Test
    void anEpicLoginOfASuperuserHoldsTheSuperuserAuthority() {
        ScimUser ada = users.given(ScimIdentities.user("eABC"));
        givenSuperuserGroup(ada);

        assertThat(authoritiesOf(mapped.loadEpicLinkedUser("eABC").orElseThrow()))
                .contains("ROLE_SUPERUSER")
                .containsAll(TestGroupPermissions.EVERY_PERMISSION);
    }

    /** An Epic Login holds what its Groups carry, as a password Login does. */
    @Test
    void anEpicLoginHoldsWhatItsGroupsCarry() {
        ScimUser ada = users.given(ScimIdentities.user("eABC"));
        groups.given(ScimGroup.created(AUDITORS, "Auditors",
                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW));

        assertThat(authoritiesOf(mapped.loadEpicLinkedUser("eABC").orElseThrow()))
                .containsExactly("ROLE_USER", "audit:read", "patient:read", "user:read");
    }

    /**
     * The reservation is applied through the port, because production has no other way to
     * produce one.
     */
    private ScimGroup givenAdminGroup(ScimUser... members) {
        return groups.createReserved(
                ScimIdentities.group("Admins", members), ReservedResourceName.ADMIN_GROUP);
    }

    /** The reserved Admin group, under the id the seed creates it under. */
    private ScimGroup givenSuperuserGroup(ScimUser... members) {
        return groups.createReserved(ScimGroup.created(TestGroupPermissions.SUPERUSER_GROUP_ID, "Admins",
                        Stream.of(members).map(member -> ScimGroupMember.reference(member.id()))
                                .toList(),
                        ScimIdentities.NOW),
                ReservedResourceName.ADMIN_GROUP);
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
     * What every active User holds whose Groups carry nothing: {@code ROLE_USER} and the baseline
     * Permissions, sorted by name as Spring Security's {@code User} keeps them.
     */
    private static final String[] BASELINE =
            {"ROLE_USER", "patient:read"};

    /** The baseline beside the Helpdesk Group's {@code user:read} and {@code account:unlock}, sorted. */
    private static final String[] HELPDESK_AUTHORITIES =
            {"ROLE_USER", "account:unlock", "patient:read", "user:read"};

    private List<String> authoritiesOf(String userName) {
        return authoritiesOf(service.loadUserByUsername(userName));
    }

    private static List<String> authoritiesOf(UserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
