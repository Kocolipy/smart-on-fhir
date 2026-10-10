package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.domain.AuditGroupAttribute;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.authorization.domain.Role;
import com.example.backend.scim.application.ScimWriteEffects.GroupState;
import com.example.backend.scim.application.ScimWriteEffects.RoleChange;
import com.example.backend.scim.application.ScimWriteEffects.SessionRevocation;
import com.example.backend.scim.application.ScimWriteEffects.Stored;
import com.example.backend.scim.application.ScimWriteEffects.UserState;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimName;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserSessions.Cause;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
 * Which SCIM edits are audited, which end Sessions and which change Roles — decided on plain
 * values, with no repository, session or audit fake. The use cases that carry these effects out
 * are covered by {@code ScimUserServiceTests} and {@code ScimGroupServiceTests}.
 */
class ScimWriteEffectsTests {

    private static final UUID USER_ID = UUID.randomUUID();

    private static final UUID GROUP_ID = UUID.randomUUID();

    private static final UUID ALICE = UUID.randomUUID();

    private static final UUID BOB = UUID.randomUUID();

    private static final UUID CAROL = UUID.randomUUID();

    private static final Role HELPDESK = new Role("Helpdesk", Set.of());

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
        assertThat(effects.roleChanges()).isEmpty();
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

    /** A Role is conferred by a mapped Group's membership, which no User write can move. */
    @Test
    void a_user_write_changes_no_role_whatever_it_moves() {
        var effects = ScimWriteEffects.ofUserWrite(USER_ID, STORED_ADA, new UserState(
                with("ada2", ScimName.NONE, null, null, null, null, false, List.of()),
                null, null));

        assertThat(effects.revocations()).isNotEmpty();
        assertThat(effects.roleChanges()).isEmpty();
    }

    @Test
    void deleting_a_user_ends_its_sessions_and_audits_no_attribute() {
        var effects = ScimWriteEffects.ofUserDeletion(USER_ID);

        assertThat(effects.revocations()).isEqualTo(userRevocation(Cause.DELETED));
        assertThat(effects.audited()).isEmpty();
        assertThat(effects.roleChanges()).isEmpty();
    }

    // ---- Group writes: what is audited ----------------------------------------------------------

    private static GroupState group(String displayName, String externalId, UUID... members) {
        return new GroupState(displayName, new LinkedHashSet<>(List.of(members)), externalId);
    }

    @Test
    void a_group_write_restating_the_stored_state_has_no_effect_even_when_mapped() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", "ext", ALICE, BOB),
                group("Helpdesk", "ext", BOB, ALICE),
                Optional.of(HELPDESK));

        assertThat(effects.audited()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
        assertThat(effects.roleChanges()).isEmpty();
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
                GROUP_ID, group("Engineering", "ext", ALICE), after, Optional.empty());

        assertThat(effects.audited()).containsExactly(attribute);
    }

    @Test
    void a_group_write_moving_every_attribute_audits_all_three() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Engineering", "ext"), group("Platform", "ext-2", ALICE), Optional.empty());

        assertThat(effects.audited()).isEqualTo(EnumSet.allOf(AuditGroupAttribute.class));
    }

    // ---- Group writes: a mapped Group's membership is Role assignment ---------------------------

    private static RoleChange granted(UUID userId) {
        return new RoleChange(RoleChange.Kind.GRANTED, userId, GROUP_ID, HELPDESK);
    }

    private static RoleChange revoked(UUID userId) {
        return new RoleChange(RoleChange.Kind.REVOKED, userId, GROUP_ID, HELPDESK);
    }

    private static SessionRevocation roleRevocation(UUID userId) {
        return new SessionRevocation(userId, EnumSet.of(Cause.ROLE_REVOKED));
    }

    @Test
    void removing_a_member_of_a_mapped_group_revokes_its_role_and_only_its_sessions() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE, BOB), group("Helpdesk", null, BOB),
                Optional.of(HELPDESK));

        assertThat(effects.roleChanges()).containsExactly(revoked(ALICE));
        assertThat(effects.revocations()).containsExactly(roleRevocation(ALICE));
    }

    /** Gaining a Role leaves live sessions alone: the Permissions arrive at the next sign-in. */
    @Test
    void adding_a_member_to_a_mapped_group_grants_its_role_and_ends_no_session() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE), group("Helpdesk", null, ALICE, BOB),
                Optional.of(HELPDESK));

        assertThat(effects.roleChanges()).containsExactly(granted(BOB));
        assertThat(effects.revocations()).isEmpty();
    }

    /** Every User dropped is revoked, in the stored order, before any User added is granted. */
    @Test
    void replacing_a_mapped_groups_members_revokes_those_dropped_before_granting_those_added() {
        UUID dave = UUID.randomUUID();
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE, BOB, CAROL), group("Helpdesk", null, CAROL, BOB, dave),
                Optional.of(HELPDESK));

        assertThat(effects.roleChanges()).containsExactly(revoked(ALICE), granted(dave));
        assertThat(effects.revocations()).containsExactly(roleRevocation(ALICE));
    }

    @Test
    void emptying_a_mapped_group_revokes_every_member_in_the_stored_order() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, BOB, ALICE), group("Helpdesk", null),
                Optional.of(HELPDESK));

        assertThat(effects.roleChanges()).containsExactly(revoked(BOB), revoked(ALICE));
        assertThat(effects.revocations())
                .containsExactly(roleRevocation(BOB), roleRevocation(ALICE));
    }

    /**
     * Decided from the membership, not the request: a User removed and added back in one PATCH,
     * or a removal naming a non-member, reaches here as an unchanged membership.
     */
    @Test
    void a_mapped_group_write_that_leaves_the_membership_as_it_was_changes_no_role() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE), group("Helpdesk", "ext", ALICE),
                Optional.of(HELPDESK));

        assertThat(effects.audited()).containsExactly(AuditGroupAttribute.EXTERNAL_ID);
        assertThat(effects.roleChanges()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    @Test
    void renaming_a_mapped_group_changes_no_role() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", null, ALICE), group("Service desk", null, ALICE),
                Optional.of(HELPDESK));

        assertThat(effects.audited()).containsExactly(AuditGroupAttribute.DISPLAY_NAME);
        assertThat(effects.roleChanges()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    /** A Group the mapping does not name confers nothing, so its membership is not power. */
    @Test
    void an_unmapped_groups_membership_change_changes_no_role_and_ends_no_session() {
        var effects = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Engineering", null, ALICE, BOB), group("Engineering", null, CAROL),
                Optional.empty());

        assertThat(effects.audited()).containsExactly(AuditGroupAttribute.MEMBERS);
        assertThat(effects.roleChanges()).isEmpty();
        assertThat(effects.revocations()).isEmpty();
    }

    // ---- Group deletion ---------------------------------------------------------------------------

    /** Deleting a mapped Group takes its Role from every member, as removing each would. */
    @Test
    void deleting_a_mapped_group_revokes_every_members_role_and_sessions() {
        var effects = ScimWriteEffects.ofGroupDeletion(
                GROUP_ID, new LinkedHashSet<>(List.of(ALICE, BOB)), Optional.of(HELPDESK));

        assertThat(effects.audited()).isEmpty();
        assertThat(effects.roleChanges()).containsExactly(revoked(ALICE), revoked(BOB));
        assertThat(effects.revocations())
                .containsExactly(roleRevocation(ALICE), roleRevocation(BOB));
    }

    @Test
    void deleting_an_unmapped_or_empty_group_has_no_effect() {
        var unmapped = ScimWriteEffects.ofGroupDeletion(GROUP_ID, Set.of(ALICE), Optional.empty());
        var empty = ScimWriteEffects.ofGroupDeletion(GROUP_ID, Set.of(), Optional.of(HELPDESK));

        assertThat(List.of(unmapped, empty)).allSatisfy(effects -> {
            assertThat(effects.audited()).isEmpty();
            assertThat(effects.roleChanges()).isEmpty();
            assertThat(effects.revocations()).isEmpty();
        });
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
                GROUP_ID, group("Engineering", "ext", ALICE), after, Optional.of(HELPDESK));

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
                Optional.empty());

        assertThat(effects.stored()).isEqualTo(new Stored(true, true));
        assertThat(effects.stored().aliasOnly()).isFalse();
    }

    @Test
    void a_group_write_restating_the_stored_state_or_a_deletion_stores_nothing() {
        var restated = ScimWriteEffects.ofGroupWrite(GROUP_ID,
                group("Helpdesk", "ext", ALICE, BOB), group("Helpdesk", "ext", BOB, ALICE),
                Optional.empty());
        var deleted = ScimWriteEffects.ofGroupDeletion(
                GROUP_ID, Set.of(ALICE), Optional.of(HELPDESK));

        assertThat(List.of(restated, deleted)).allSatisfy(effects -> {
            assertThat(effects.stored()).isEqualTo(Stored.NOTHING);
            assertThat(effects.stored().aliasOnly()).isFalse();
        });
    }

    // ---- the values themselves ---------------------------------------------------------------------

    @Nested
    class Values {

        @Test
        void a_revocation_without_a_cause_is_refused() {
            assertThatThrownBy(() -> new SessionRevocation(USER_ID, EnumSet.noneOf(Cause.class)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** A use case reads what to store on every write, so effects always say. */
        @Test
        void effects_without_what_to_store_are_refused() {
            assertThatThrownBy(() -> new ScimWriteEffects<>(
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
            causes.add(Cause.ROLE_REVOKED);
            assertThat(revocation.userId()).isEqualTo(USER_ID);
            assertThat(revocation.causes()).containsExactly(Cause.DELETED);

            Set<AuditUserAttribute> audited = EnumSet.of(AuditUserAttribute.ACTIVE);
            List<SessionRevocation> revocations = new ArrayList<>(List.of(revocation));
            List<RoleChange> roleChanges = new ArrayList<>(List.of(revoked(ALICE)));
            var effects = new ScimWriteEffects<>(
                    audited, revocations, roleChanges, Stored.NOTHING);
            audited.add(AuditUserAttribute.NAME);
            revocations.clear();
            roleChanges.clear();
            assertThat(effects.audited()).containsExactly(AuditUserAttribute.ACTIVE);
            assertThat(effects.revocations()).containsExactly(revocation);
            assertThat(effects.roleChanges()).containsExactly(revoked(ALICE));

            assertThatThrownBy(() -> effects.audited().add(AuditUserAttribute.NAME))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> before.memberIds().add(BOB))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> revocation.causes().add(Cause.ROLE_REVOKED))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
