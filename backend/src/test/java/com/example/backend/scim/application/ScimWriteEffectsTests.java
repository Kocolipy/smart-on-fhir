package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.domain.AuditGroupAttribute;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.scim.application.ScimWriteEffects.GroupState;
import com.example.backend.scim.application.ScimWriteEffects.MembershipChange;
import com.example.backend.scim.application.ScimWriteEffects.Resource;
import com.example.backend.scim.application.ScimWriteEffects.SessionRevocation;
import com.example.backend.scim.application.ScimWriteEffects.Stored;
import com.example.backend.scim.application.ScimWriteEffects.UserState;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimName;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserSessions.Cause;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which SCIM edits are audited, which end Sessions and which change Permissions — decided on plain
 * values, with no repository, session or audit fake. The use cases that carry these effects out
 * are covered by {@code ScimUserServiceTests} and {@code ScimGroupServiceTests}.
 */
class ScimWriteEffectsTests {

    private static final UUID USER_ID = UUID.randomUUID();

    private static final UUID GROUP_ID = UUID.randomUUID();

    private static final UUID ALICE = UUID.randomUUID();

    private static final UUID BOB = UUID.randomUUID();

    private static final UUID CAROL = UUID.randomUUID();

    /** A Group whose membership confers Permissions, or the Admin group. */
    private static final boolean CONFERS = true;

    /** A Group whose membership confers nothing. */
    private static final boolean CONFERS_NOTHING = false;

    private static final ScimUserProfile ADA = new ScimUserProfile(
            "ada", new ScimName(null, "King", "Ada", null, null, null),
            "Ada", "en", "en-GB", "Europe/London", true,
            List.of(new ScimEmail("ada@work.example", "work", true)));

    private static final UserState STORED_ADA = new UserState(ADA, "ext-ada", "hash-1");

    // ---- User writes: what is audited ---------------------------------------------------------

    private static ScimUserProfile with(
            String userName, ScimName name, String displayName, String preferredLanguage,
            String locale, String timezone, boolean active, List<ScimEmail> emails) {
        return new ScimUserProfile(
                userName, name, displayName, preferredLanguage, locale, timezone, active, emails);
    }

    /** Each attribute moved on its own, and the one audit attribute it must name. */
    static Stream<Arguments> userAttributeMoves() {
        ScimUserProfile p = ADA;
        return Stream.of(
                Arguments.of(AuditUserAttribute.USER_NAME, (UnaryOperator<UserState>) s ->
                        new UserState(with("ada2", p.name(), p.displayName(),
                                p.preferredLanguage(), p.locale(), p.timezone(), p.active(),
                                p.emails()), s.externalId(), s.passwordHash())),
                Arguments.of(AuditUserAttribute.NAME, (UnaryOperator<UserState>) s ->
                        new UserState(with(p.userName(), ScimName.NONE, p.displayName(),
                                p.preferredLanguage(), p.locale(), p.timezone(), p.active(),
                                p.emails()), s.externalId(), s.passwordHash())),
                Arguments.of(AuditUserAttribute.DISPLAY_NAME, (UnaryOperator<UserState>) s ->
                        new UserState(with(p.userName(), p.name(), "Countess",
                                p.preferredLanguage(), p.locale(), p.timezone(), p.active(),
                                p.emails()), s.externalId(), s.passwordHash())),
                Arguments.of(AuditUserAttribute.PREFERRED_LANGUAGE, (UnaryOperator<UserState>) s ->
                        new UserState(with(p.userName(), p.name(), p.displayName(), "fr",
                                p.locale(), p.timezone(), p.active(), p.emails()),
                                s.externalId(), s.passwordHash())),
                Arguments.of(AuditUserAttribute.LOCALE, (UnaryOperator<UserState>) s ->
                        new UserState(with(p.userName(), p.name(), p.displayName(),
                                p.preferredLanguage(), "fr-FR", p.timezone(), p.active(),
                                p.emails()), s.externalId(), s.passwordHash())),
                Arguments.of(AuditUserAttribute.TIMEZONE, (UnaryOperator<UserState>) s ->
                        new UserState(with(p.userName(), p.name(), p.displayName(),
                                p.preferredLanguage(), p.locale(), "Europe/Paris", p.active(),
                                p.emails()), s.externalId(), s.passwordHash())),
                Arguments.of(AuditUserAttribute.ACTIVE, (UnaryOperator<UserState>) s ->
                        new UserState(with(p.userName(), p.name(), p.displayName(),
                                p.preferredLanguage(), p.locale(), p.timezone(), false,
                                p.emails()), s.externalId(), s.passwordHash())),
                Arguments.of(AuditUserAttribute.EMAILS, (UnaryOperator<UserState>) s ->
                        new UserState(with(p.userName(), p.name(), p.displayName(),
                                p.preferredLanguage(), p.locale(), p.timezone(), p.active(),
                                List.of()), s.externalId(), s.passwordHash())),
                Arguments.of(AuditUserAttribute.PASSWORD, (UnaryOperator<UserState>) s ->
                        new UserState(s.profile(), s.externalId(), "hash-2")),
                Arguments.of(AuditUserAttribute.EXTERNAL_ID, (UnaryOperator<UserState>) s ->
                        new UserState(s.profile(), "ext-ada-2", s.passwordHash())));
    }

    @ParameterizedTest
    @MethodSource("userAttributeMoves")
    void a_user_write_audits_exactly_the_attribute_that_moved(
            AuditUserAttribute attribute, UnaryOperator<UserState> edit) {
        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, edit.apply(STORED_ADA));

        assertThat(effects.audited()).containsExactly(attribute);
    }

    @Test
    void a_user_write_that_moves_everything_audits_every_attribute() {
        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, new UserState(
                with("ada2", ScimName.NONE, null, "fr", "fr-FR", "Europe/Paris", false, List.of()),
                null, null));

        // GROUPS is the one attribute a User write cannot move: it is computed from the Groups.
        assertThat(effects.audited()).isEqualTo(
                EnumSet.complementOf(EnumSet.of(AuditUserAttribute.GROUPS)));
    }

    /** A PUT resending the stored state, or a PATCH whose operations cancel out, moved nothing. */
    @Test
    void a_user_write_restating_the_stored_state_has_no_effect() {
        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, new UserState(
                with("ada", new ScimName(null, "King", "Ada", null, null, null), "Ada", "en",
                        "en-GB", "Europe/London", true,
                        List.of(new ScimEmail("ada@work.example", "work", true))),
                "ext-ada", "hash-1"));

        assertThat(effects.audited()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
        assertThat(effects.membershipChanges()).isEmpty();
    }

    // ---- User writes: which edits end Sessions -------------------------------------------------

    private static UserState active(UserState state, boolean active) {
        ScimUserProfile p = state.profile();
        return new UserState(with(p.userName(), p.name(), p.displayName(), p.preferredLanguage(),
                p.locale(), p.timezone(), active, p.emails()), state.externalId(),
                state.passwordHash());
    }

    private static UserState password(UserState state, String passwordHash) {
        return new UserState(state.profile(), state.externalId(), passwordHash);
    }

    private static List<SessionRevocation> userRevocation(Cause... causes) {
        return List.of(new SessionRevocation(USER_ID, EnumSet.copyOf(List.of(causes))));
    }

    @Test
    void deactivation_ends_the_users_sessions() {
        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, active(STORED_ADA, false));

        assertThat(effects.revocations()).isEqualTo(userRevocation(Cause.DEACTIVATED));
    }

    /** Only the transition from active to inactive is a deactivation. */
    @Test
    void reactivation_or_staying_inactive_ends_no_session() {
        UserState inactive = active(STORED_ADA, false);

        assertThat(ScimWriteEffects.ofUserWrite(USER_ID, inactive, STORED_ADA).revocations())
                .as("reactivation")
                .isEmpty();
        UserState renamedInactive = new UserState(
                with("ada", ADA.name(), "Countess", ADA.preferredLanguage(), ADA.locale(),
                        ADA.timezone(), false, ADA.emails()), "ext-ada", "hash-1");
        assertThat(ScimWriteEffects.ofUserWrite(USER_ID, inactive, renamedInactive).revocations())
                .as("staying inactive")
                .isEmpty();
    }

    /** Set over none, changed, or removed: each changes the credential a session was issued against. */
    @Test
    void a_password_set_changed_or_removed_ends_the_users_sessions() {
        assertThat(ScimWriteEffects.ofUserWrite(
                        USER_ID, STORED_ADA, password(STORED_ADA, "hash-2")).revocations())
                .isEqualTo(userRevocation(Cause.PASSWORD_CHANGED));
        assertThat(ScimWriteEffects.ofUserWrite(
                        USER_ID, STORED_ADA, password(STORED_ADA, null)).revocations())
                .isEqualTo(userRevocation(Cause.PASSWORD_CHANGED));
        assertThat(ScimWriteEffects.ofUserWrite(
                        USER_ID, password(STORED_ADA, null), STORED_ADA).revocations())
                .isEqualTo(userRevocation(Cause.PASSWORD_CHANGED));
    }

    /** Removing a password that is not there changes nothing, so it ends nothing. */
    @Test
    void removing_an_absent_password_has_no_effect() {
        UserState credentialless = password(STORED_ADA, null);

        var effects = ScimWriteEffects.ofUserWrite(USER_ID, credentialless, credentialless);

        assertThat(effects.audited()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    @Test
    void a_user_name_change_ends_the_users_sessions() {
        UserState renamed = new UserState(with("ada.lovelace", ADA.name(), ADA.displayName(),
                ADA.preferredLanguage(), ADA.locale(), ADA.timezone(), true, ADA.emails()),
                "ext-ada", "hash-1");

        assertThat(ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, renamed).revocations())
                .isEqualTo(userRevocation(Cause.USER_NAME_CHANGED));
    }

    @Test
    void one_write_changing_several_security_attributes_ends_the_sessions_once_with_every_cause() {
        UserState after = new UserState(
                with("ada.lovelace", ADA.name(), ADA.displayName(), ADA.preferredLanguage(),
                        ADA.locale(), ADA.timezone(), false, ADA.emails()),
                "ext-ada", "hash-2");

        assertThat(ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, after).revocations())
                .isEqualTo(userRevocation(
                        Cause.DEACTIVATED, Cause.PASSWORD_CHANGED, Cause.USER_NAME_CHANGED));
    }

    /** None of these is something a session was issued against. */
    @Test
    void a_profile_email_or_alias_change_ends_no_session() {
        UserState after = new UserState(
                with("ada", ScimName.NONE, "Countess", "fr", "fr-FR", "Europe/Paris", true,
                        List.of()),
                "ext-ada-2", "hash-1");

        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, after);

        assertThat(effects.audited()).isNotEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    /** Permissions are conferred by a Group's membership, which no User write can move. */
    @Test
    void a_user_write_changes_no_permission_whatever_it_moves() {
        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, new UserState(
                with("ada2", ScimName.NONE, null, null, null, null, false, List.of()),
                null, null));

        assertThat(effects.revocations()).isNotEmpty();
        assertThat(effects.membershipChanges()).isEmpty();
    }

    @Test
    void deleting_a_user_ends_its_sessions_and_audits_no_attribute() {
        var effects = ScimWriteEffects.ofUserDeletion(USER_ID);

        assertThat(effects.revocations()).isEqualTo(userRevocation(Cause.DELETED));
        assertThat(effects.audited()).isEmpty();
        assertThat(effects.membershipChanges()).isEmpty();
    }

    // ---- Group writes: what is audited ----------------------------------------------------------

    private static GroupState group(String displayName, String externalId, UUID... members) {
        return new GroupState(displayName, new LinkedHashSet<>(List.of(members)), externalId);
    }

    @Test
    void a_group_write_restating_the_stored_state_has_no_effect_even_when_it_confers_permissions() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", "ext", ALICE, BOB),
                group("Helpdesk", "ext", BOB, ALICE),
                CONFERS);

        assertThat(effects.audited()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
        assertThat(effects.membershipChanges()).isEmpty();
    }

    static Stream<Arguments> groupAttributeMoves() {
        return Stream.of(
                Arguments.of(AuditGroupAttribute.DISPLAY_NAME, group("Platform", "ext", ALICE)),
                Arguments.of(AuditGroupAttribute.MEMBERS, group("Engineering", "ext", ALICE, BOB)),
                Arguments.of(AuditGroupAttribute.EXTERNAL_ID, group("Engineering", null, ALICE)));
    }

    @ParameterizedTest
    @MethodSource("groupAttributeMoves")
    void a_group_write_audits_exactly_the_attribute_that_moved(
            AuditGroupAttribute attribute, GroupState after) {
        var effects = ScimWriteEffects.ofGroupWrite(
                GROUP_ID, group("Engineering", "ext", ALICE), after, CONFERS_NOTHING);

        assertThat(effects.audited()).containsExactly(attribute);
    }

    @Test
    void a_group_write_moving_every_attribute_audits_all_three() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Engineering", "ext"), group("Platform", "ext-2", ALICE), CONFERS_NOTHING);

        // A Group's Permissions are a Superuser's to set, never a connector's write.
        assertThat(effects.audited()).isEqualTo(EnumSet.of(AuditGroupAttribute.DISPLAY_NAME,
                AuditGroupAttribute.MEMBERS, AuditGroupAttribute.EXTERNAL_ID));
    }

    // ---- Group writes: a membership of a Group that confers Permissions is a change of power ----

    private static MembershipChange granted(UUID userId) {
        return new MembershipChange(MembershipChange.Kind.GRANTED, userId, GROUP_ID);
    }

    private static MembershipChange revoked(UUID userId) {
        return new MembershipChange(MembershipChange.Kind.REVOKED, userId, GROUP_ID);
    }

    private static SessionRevocation membershipRevocation(UUID userId) {
        return new SessionRevocation(userId, EnumSet.of(Cause.MEMBERSHIP_REVOKED));
    }

    @Test
    void removing_a_member_of_a_conferring_group_revokes_its_membership_and_only_its_sessions() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE, BOB), group("Helpdesk", null, BOB),
                CONFERS);

        assertThat(effects.membershipChanges()).containsExactly(revoked(ALICE));
        assertThat(effects.revocations()).containsExactly(membershipRevocation(ALICE));
    }

    /** Gaining Permissions leaves live sessions alone: the Permissions arrive at the next sign-in. */
    @Test
    void adding_a_member_to_a_conferring_group_grants_and_ends_no_session() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE), group("Helpdesk", null, ALICE, BOB),
                CONFERS);

        assertThat(effects.membershipChanges()).containsExactly(granted(BOB));
        assertThat(effects.revocations()).isEmpty();
    }

    /** Every User dropped is revoked, in the stored order, before any User added is granted. */
    @Test
    void replacing_a_conferring_groups_members_revokes_those_dropped_before_granting_those_added() {
        UUID dave = UUID.randomUUID();
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE, BOB, CAROL), group("Helpdesk", null, CAROL, BOB, dave),
                CONFERS);

        assertThat(effects.membershipChanges()).containsExactly(revoked(ALICE), granted(dave));
        assertThat(effects.revocations()).containsExactly(membershipRevocation(ALICE));
    }

    @Test
    void emptying_a_conferring_group_revokes_every_member_in_the_stored_order() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, BOB, ALICE), group("Helpdesk", null),
                CONFERS);

        assertThat(effects.membershipChanges()).containsExactly(revoked(BOB), revoked(ALICE));
        assertThat(effects.revocations())
                .containsExactly(membershipRevocation(BOB), membershipRevocation(ALICE));
    }

    /**
     * Decided from the membership, not the request: a User removed and added back in one PATCH,
     * or a removal naming a non-member, reaches here as an unchanged membership.
     */
    @Test
    void a_conferring_group_write_that_leaves_the_membership_as_it_was_changes_nothing() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE), group("Helpdesk", "ext", ALICE),
                CONFERS);

        assertThat(effects.audited()).containsExactly(AuditGroupAttribute.EXTERNAL_ID);
        assertThat(effects.membershipChanges()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    @Test
    void renaming_a_conferring_group_changes_no_membership() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE), group("Service desk", null, ALICE),
                CONFERS);

        assertThat(effects.audited()).containsExactly(AuditGroupAttribute.DISPLAY_NAME);
        assertThat(effects.membershipChanges()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    /** A Group the mapping does not name confers nothing, so its membership is not power. */
    @Test
    void a_membership_change_of_a_group_conferring_nothing_ends_no_session() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Engineering", null, ALICE, BOB), group("Engineering", null, CAROL),
                CONFERS_NOTHING);

        assertThat(effects.audited()).containsExactly(AuditGroupAttribute.MEMBERS);
        assertThat(effects.membershipChanges()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    // ---- Group deletion ---------------------------------------------------------------------------

    /** Deleting a conferring Group takes its Permissions from every member, as removing each would. */
    @Test
    void deleting_a_conferring_group_revokes_every_membership_and_session() {
        var effects = ScimWriteEffects.ofGroupDeletion(
                GROUP_ID, new LinkedHashSet<>(List.of(ALICE, BOB)), CONFERS);

        assertThat(effects.audited()).isEmpty();
        assertThat(effects.membershipChanges()).containsExactly(revoked(ALICE), revoked(BOB));
        assertThat(effects.revocations())
                .containsExactly(membershipRevocation(ALICE), membershipRevocation(BOB));
    }

    @Test
    void deleting_a_group_conferring_nothing_or_an_empty_one_has_no_effect() {
        var conferringNothing = ScimWriteEffects.ofGroupDeletion(GROUP_ID, Set.of(ALICE), CONFERS_NOTHING);
        var empty = ScimWriteEffects.ofGroupDeletion(GROUP_ID, Set.of(), CONFERS);

        assertThat(List.of(conferringNothing, empty)).allSatisfy(effects -> {
            assertThat(effects.audited()).isEmpty();
            assertThat(effects.membershipChanges()).isEmpty();
            assertThat(effects.revocations()).isEmpty();
        });
    }

    // ---- the dormancy job's membership revocation -------------------------------------------------

    /**
     * The dormancy job removed a User from several Groups at once — whatever each confers: it
     * loses each membership, in the order they were removed, and its Sessions end once, not once
     * per Group.
     */
    @Test
    void a_membership_revocation_revokes_each_membership_and_ends_the_sessions_once() {
        UUID other = UUID.randomUUID();

        var effects = ScimWriteEffects.ofMembershipRevocation(ALICE, List.of(GROUP_ID, other));

        assertThat(effects.audited()).isEmpty();
        assertThat(effects.membershipChanges()).containsExactly(
                revoked(ALICE),
                new MembershipChange(MembershipChange.Kind.REVOKED, ALICE, other));
        assertThat(effects.revocations()).containsExactly(membershipRevocation(ALICE));
        assertThat(effects.stored()).isEqualTo(Stored.NOTHING);
    }

    /** A User that held none of the memberships any more lost nothing. */
    @Test
    void a_membership_revocation_that_removed_nothing_has_no_effect() {
        var effects = ScimWriteEffects.ofMembershipRevocation(ALICE, List.of());

        assertThat(effects.membershipChanges()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    // ---- what the write stores ---------------------------------------------------------------------

    /**
     * Every User column, moved on its own, is a column write and no alias write; the alias, moved
     * on its own, is the reverse. Decided beside the audit set, not read out of it.
     */
    @ParameterizedTest
    @MethodSource("userAttributeMoves")
    void a_user_write_stores_the_columns_or_the_alias_it_moved(
            AuditUserAttribute attribute, UnaryOperator<UserState> edit) {
        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, edit.apply(STORED_ADA));

        boolean alias = attribute == AuditUserAttribute.EXTERNAL_ID;
        assertThat(effects.stored()).isEqualTo(new Stored(!alias, alias));
        assertThat(effects.stored().nothing()).isFalse();
    }

    @Test
    void a_user_write_moving_columns_and_alias_stores_both() {
        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA,
                new UserState(active(STORED_ADA, false).profile(), "ext-ada-2", "hash-1"));

        assertThat(effects.stored()).isEqualTo(new Stored(true, true));
        assertThat(effects.stored().nothing()).isFalse();
        assertThat(effects.stored().aliasOnly()).isFalse();
    }

    @Test
    void a_user_write_restating_the_stored_state_or_a_deletion_stores_nothing() {
        var restated = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, STORED_ADA);
        UserState credentialless = password(STORED_ADA, null);
        var absentRemoved = ScimWriteEffects.ofUserWrite(USER_ID, credentialless, credentialless);

        assertThat(List.of(restated, absentRemoved, ScimWriteEffects.ofUserDeletion(USER_ID)))
                .allSatisfy(effects -> {
                    assertThat(effects.stored()).isEqualTo(Stored.NOTHING);
                    assertThat(effects.stored().nothing()).isTrue();
                });
    }

    @ParameterizedTest
    @MethodSource("groupAttributeMoves")
    void a_group_write_stores_the_columns_or_the_alias_it_moved(
            AuditGroupAttribute attribute, GroupState after) {
        var effects = ScimWriteEffects.ofGroupWrite(
                GROUP_ID, group("Engineering", "ext", ALICE), after, CONFERS);

        boolean alias = attribute == AuditGroupAttribute.EXTERNAL_ID;
        assertThat(effects.stored()).isEqualTo(new Stored(!alias, alias));
        assertThat(effects.stored().aliasOnly()).isEqualTo(alias);
        assertThat(effects.stored().nothing()).isFalse();
    }

    /** A write that moved a column advances the version itself, so the alias is not alone. */
    @Test
    void a_group_write_moving_a_column_and_the_alias_is_not_alias_only() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Engineering", "ext", ALICE), group("Platform", "ext-2", ALICE),
                CONFERS_NOTHING);

        assertThat(effects.stored()).isEqualTo(new Stored(true, true));
        assertThat(effects.stored().aliasOnly()).isFalse();
    }

    @Test
    void a_group_write_restating_the_stored_state_or_a_deletion_stores_nothing() {
        var restated = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", "ext", ALICE, BOB), group("Helpdesk", "ext", BOB, ALICE),
                CONFERS_NOTHING);
        var deleted = ScimWriteEffects.ofGroupDeletion(
                GROUP_ID, Set.of(ALICE), CONFERS);

        assertThat(List.of(restated, deleted)).allSatisfy(effects -> {
            assertThat(effects.stored()).isEqualTo(Stored.NOTHING);
            assertThat(effects.stored().aliasOnly()).isFalse();
        });
    }

    // ---- the values themselves ---------------------------------------------------------------------

    @Nested
    class Values {

        /**
         * Every effect names the resource written: the one an alias written alone advances. The
         * dormancy job's revocation is a write to the User it revoked, not to its Groups.
         */
        @Test
        void each_effect_names_the_resource_it_was_written_to() {
            GroupState group = new GroupState("Helpdesk", Set.of(ALICE), null);
            assertThat(List.of(
                    ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, STORED_ADA).written(),
                    ScimWriteEffects.ofUserDeletion(USER_ID).written(),
                    ScimWriteEffects.ofMembershipRevocation(USER_ID, List.of(GROUP_ID))
                            .written(),
                    ScimWriteEffects.ofGroupWrite(GROUP_ID, group, group, CONFERS_NOTHING)
                            .written(),
                    ScimWriteEffects.ofGroupWrite(GROUP_ID, group, group, CONFERS)
                            .written(),
                    ScimWriteEffects.ofGroupDeletion(GROUP_ID, Set.of(ALICE), CONFERS_NOTHING)
                            .written()))
                    .containsExactly(
                            new Resource(ScimResourceType.USER, USER_ID),
                            new Resource(ScimResourceType.USER, USER_ID),
                            new Resource(ScimResourceType.USER, USER_ID),
                            new Resource(ScimResourceType.GROUP, GROUP_ID),
                            new Resource(ScimResourceType.GROUP, GROUP_ID),
                            new Resource(ScimResourceType.GROUP, GROUP_ID));
        }

        @Test
        void effects_written_to_no_resource_are_refused() {
            assertThatThrownBy(() -> new ScimWriteEffects<>(null,
                    EnumSet.noneOf(AuditUserAttribute.class), List.of(), List.of(), Stored.NOTHING))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("written");
        }

        @Test
        void a_resource_without_a_type_or_an_id_is_refused() {
            assertThatThrownBy(() -> new Resource(null, USER_ID))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("type");
            assertThatThrownBy(() -> new Resource(ScimResourceType.USER, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("id");
        }

        @Test
        void a_revocation_without_a_cause_is_refused() {
            assertThatThrownBy(() -> new SessionRevocation(USER_ID, EnumSet.noneOf(Cause.class)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** A use case reads what to store on every write, so effects always say. */
        @Test
        void effects_without_what_to_store_are_refused() {
            assertThatThrownBy(() -> new ScimWriteEffects<>(Resource.user(USER_ID),
                    EnumSet.noneOf(AuditUserAttribute.class), List.of(), List.of(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("stored");
        }

        /** The effects a use case carries out cannot be altered by it, nor by the inputs after. */
        @Test
        void effects_and_their_inputs_are_copied_and_unmodifiable() {
            Set<UUID> members = new LinkedHashSet<>(List.of(ALICE));
            GroupState before = new GroupState("Helpdesk", members, null);
            members.add(BOB);
            assertThat(before.memberIds()).containsExactly(ALICE);

            Set<Cause> causes = EnumSet.of(Cause.DELETED);
            SessionRevocation revocation = new SessionRevocation(USER_ID, causes);
            causes.add(Cause.MEMBERSHIP_REVOKED);
            assertThat(revocation.userId()).isEqualTo(USER_ID);
            assertThat(revocation.causes()).containsExactly(Cause.DELETED);

            Set<AuditUserAttribute> audited = EnumSet.of(AuditUserAttribute.ACTIVE);
            List<SessionRevocation> revocations = new ArrayList<>(List.of(revocation));
            List<MembershipChange> changes = new ArrayList<>(List.of(revoked(ALICE)));
            var effects = new ScimWriteEffects<>(
                    Resource.user(USER_ID), audited, revocations, changes, Stored.NOTHING);
            audited.add(AuditUserAttribute.NAME);
            revocations.clear();
            changes.clear();
            assertThat(effects.audited()).containsExactly(AuditUserAttribute.ACTIVE);
            assertThat(effects.revocations()).containsExactly(revocation);
            assertThat(effects.membershipChanges()).containsExactly(revoked(ALICE));

            assertThatThrownBy(() -> effects.audited().add(AuditUserAttribute.NAME))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> before.memberIds().add(BOB))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> revocation.causes().add(Cause.MEMBERSHIP_REVOKED))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
