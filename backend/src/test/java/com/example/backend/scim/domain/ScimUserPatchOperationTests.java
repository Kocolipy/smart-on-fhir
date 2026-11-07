package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.ScimUserPatchOperation.AddEmails;
import com.example.backend.scim.domain.ScimUserPatchOperation.EmailUpdate;
import com.example.backend.scim.domain.ScimUserPatchOperation.MergeName;
import com.example.backend.scim.domain.ScimUserPatchOperation.NamePart;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveEmailPart;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveEmails;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveName;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveNamePart;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemovePassword;
import com.example.backend.scim.domain.ScimUserPatchOperation.RemoveText;
import com.example.backend.scim.domain.ScimUserPatchOperation.ReplaceEmails;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetActive;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetPassword;
import com.example.backend.scim.domain.ScimUserPatchOperation.SetText;
import com.example.backend.scim.domain.ScimUserPatchOperation.TextAttribute;
import com.example.backend.scim.domain.ScimUserPatchOperation.UpdateEmails;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What each PATCH operation does to the edit, and the two refusals only the stored state can
 * decide: removing a required value ({@code mutability}) and a filtered update that selects nothing
 * ({@code noTarget}).
 */
class ScimUserPatchOperationTests {

    private static final ScimEmail WORK = new ScimEmail("ada@work.example", "work", true);

    private static final ScimEmail HOME = new ScimEmail("ada@home.example", "home", false);

    private static final ScimName NAME =
            new ScimName("Ada King", "King", "Ada", "Byron", "Lady", "PhD");

    private static final ScimUserEdit START = ScimUserEdit.of(
            new ScimUserProfile(
                    "ada", NAME, "Ada", "en", "en-GB", "Europe/London", true,
                    List.of(WORK, HOME)),
            "ext-1");

    private static ScimUserEdit fold(ScimUserPatchOperation... operations) {
        return ScimUserPatchOperation.fold(START, List.of(operations));
    }

    private static ScimEmailFilter typeIs(String type) {
        return new ScimEmailFilter(
                List.of(new ScimEmailFilter.Condition(ScimEmailPart.TYPE, type)));
    }

    // ---- single-valued attributes ---------------------------------------------------------

    @Test
    void set_text_writes_exactly_the_named_attribute() {
        assertThat(fold(new SetText(TextAttribute.USER_NAME, "ada2")).profile())
                .isEqualTo(new ScimUserProfile("ada2", NAME, "Ada", "en", "en-GB",
                        "Europe/London", true, List.of(WORK, HOME)));
        assertThat(fold(new SetText(TextAttribute.DISPLAY_NAME, "Countess")).profile()
                .displayName()).isEqualTo("Countess");
        assertThat(fold(new SetText(TextAttribute.PREFERRED_LANGUAGE, "fr")).profile()
                .preferredLanguage()).isEqualTo("fr");
        assertThat(fold(new SetText(TextAttribute.LOCALE, "fr-FR")).profile().locale())
                .isEqualTo("fr-FR");
        assertThat(fold(new SetText(TextAttribute.TIMEZONE, "Europe/Paris")).profile()
                .timezone()).isEqualTo("Europe/Paris");
        assertThat(fold(new SetText(TextAttribute.USER_NAME, "ada2")).externalId())
                .as("a text operation leaves the alias").isEqualTo("ext-1");
    }

    /** The alias is set and removed on its own, leaving the profile and credential alone. */
    @Test
    void set_and_remove_external_id_change_only_the_alias() {
        ScimUserEdit set = fold(new ScimUserPatchOperation.SetExternalId("ext-2"));
        assertThat(set.externalId()).isEqualTo("ext-2");
        assertThat(set.profile()).isEqualTo(START.profile());
        assertThat(set.password()).isEqualTo(ScimPasswordChange.UNCHANGED);

        ScimUserEdit removed = fold(new ScimUserPatchOperation.RemoveExternalId());
        assertThat(removed.externalId()).isNull();
        assertThat(removed.profile()).isEqualTo(START.profile());

        assertThatThrownBy(() -> new ScimUserPatchOperation.SetExternalId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remove_text_unassigns_an_optional_attribute_and_leaves_the_rest() {
        ScimUserEdit removed = fold(new RemoveText(TextAttribute.DISPLAY_NAME));

        assertThat(removed.profile().displayName()).isNull();
        assertThat(removed.profile().userName()).isEqualTo("ada");
        assertThat(removed.profile().locale()).isEqualTo("en-GB");
        assertThat(fold(new RemoveText(TextAttribute.PREFERRED_LANGUAGE)).profile()
                .preferredLanguage()).isNull();
        assertThat(fold(new RemoveText(TextAttribute.LOCALE)).profile().locale()).isNull();
        assertThat(fold(new RemoveText(TextAttribute.TIMEZONE)).profile().timezone()).isNull();
    }

    @Test
    void removing_the_required_user_name_is_a_mutability_refusal() {
        assertThatThrownBy(() -> fold(new RemoveText(TextAttribute.USER_NAME)))
                .isInstanceOfSatisfying(ScimPatchRefusedException.class, refused ->
                        assertThat(refused.reason())
                                .isEqualTo(ScimPatchRefusedException.Reason.MUTABILITY));
    }

    @Test
    void active_is_set_in_both_directions() {
        ScimUserEdit deactivated = fold(new SetActive(false));

        assertThat(deactivated.profile()).isEqualTo(new ScimUserProfile(
                "ada", NAME, "Ada", "en", "en-GB", "Europe/London", false, List.of(WORK, HOME)));
        assertThat(ScimUserPatchOperation.fold(deactivated, List.of(new SetActive(true)))
                .profile().active()).isTrue();
    }

    /** A set always names what it sets, so a null attribute, value or password is a bug. */
    @Test
    void a_set_without_its_attribute_or_value_cannot_be_built() {
        assertThatThrownBy(() -> new SetText(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SetText(TextAttribute.LOCALE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SetPassword(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void password_is_set_or_cleared_and_the_profile_is_left_alone() {
        ScimUserEdit set = fold(new SetPassword("a-new-long-password"));
        assertThat(set.password()).isEqualTo(ScimPasswordChange.set("a-new-long-password"));
        assertThat(set.profile()).isEqualTo(START.profile());

        assertThat(fold(new RemovePassword()).password()).isEqualTo(ScimPasswordChange.CLEAR);
        assertThat(START.password()).isEqualTo(ScimPasswordChange.UNCHANGED);
    }

    @Test
    void a_password_operation_never_prints_the_password() {
        assertThat(new SetPassword("hunter2-hunter2").toString())
                .isEqualTo("SetPassword[redacted]");
        assertThat(ScimPasswordChange.set("hunter2-hunter2").toString())
                .isEqualTo("ScimPasswordChange[SET]");
    }

    // ---- name -----------------------------------------------------------------------------

    @Test
    void merging_a_name_replaces_only_the_named_parts() {
        ScimName merged = fold(new MergeName(
                new ScimName(null, "Lovelace", null, null, null, null))).profile().name();

        assertThat(merged).isEqualTo(new ScimName("Ada King", "Lovelace", "Ada", "Byron",
                "Lady", "PhD"));
        assertThat(fold(new MergeName(new ScimName(null, null, "Augusta", null, null, null)))
                .profile().name())
                .isEqualTo(new ScimName("Ada King", "King", "Augusta", "Byron", "Lady", "PhD"));
    }

    @Test
    void merging_every_part_replaces_every_part() {
        ScimName replacement = new ScimName("f", "fa", "gi", "mi", "hp", "hs");

        assertThat(fold(new MergeName(replacement)).profile().name()).isEqualTo(replacement);
    }

    @Test
    void removing_the_name_unassigns_it_whole() {
        assertThat(fold(new RemoveName()).profile().name()).isEqualTo(ScimName.NONE);
    }

    @ParameterizedTest
    @EnumSource(NamePart.class)
    void removing_one_name_part_clears_exactly_that_part(NamePart part) {
        ScimName cleared = fold(new RemoveNamePart(part)).profile().name();

        assertThat(cleared.formatted()).isEqualTo(
                part == NamePart.FORMATTED ? null : NAME.formatted());
        assertThat(cleared.familyName()).isEqualTo(
                part == NamePart.FAMILY_NAME ? null : NAME.familyName());
        assertThat(cleared.givenName()).isEqualTo(
                part == NamePart.GIVEN_NAME ? null : NAME.givenName());
        assertThat(cleared.middleName()).isEqualTo(
                part == NamePart.MIDDLE_NAME ? null : NAME.middleName());
        assertThat(cleared.honorificPrefix()).isEqualTo(
                part == NamePart.HONORIFIC_PREFIX ? null : NAME.honorificPrefix());
        assertThat(cleared.honorificSuffix()).isEqualTo(
                part == NamePart.HONORIFIC_SUFFIX ? null : NAME.honorificSuffix());
    }

    // ---- emails ---------------------------------------------------------------------------

    @Test
    void adding_an_email_appends_it_and_adding_a_present_one_changes_nothing() {
        ScimEmail other = new ScimEmail("ada@other.example", "other", false);

        assertThat(fold(new AddEmails(List.of(other))).profile().emails())
                .containsExactly(WORK, HOME, other);
        assertThat(fold(new AddEmails(List.of(HOME))).profile()).isEqualTo(START.profile());
    }

    /** A new primary takes primacy; the canonical form alone would keep the stored one. */
    @Test
    void adding_a_primary_email_demotes_the_stored_primary() {
        ScimEmail newPrimary = new ScimEmail("ada@new.example", "other", true);

        assertThat(fold(new AddEmails(List.of(newPrimary))).profile().emails()).containsExactly(
                new ScimEmail(WORK.value(), WORK.type(), false), HOME, newPrimary);
    }

    @Test
    void replacing_the_emails_replaces_the_whole_list() {
        ScimEmail only = new ScimEmail("ada@only.example", null, false);

        assertThat(fold(new ReplaceEmails(List.of(only))).profile().emails())
                .containsExactly(only);
    }

    @Test
    void removing_emails_without_a_filter_removes_every_one() {
        assertThat(fold(new RemoveEmails(ScimEmailFilter.ALL)).profile().emails()).isEmpty();
    }

    @Test
    void removing_emails_with_a_filter_removes_only_the_matching_ones() {
        assertThat(fold(new RemoveEmails(typeIs("WORK"))).profile().emails())
                .containsExactly(HOME);
    }

    @Test
    void updating_filtered_emails_writes_only_the_named_sub_attributes_on_matches() {
        assertThat(fold(new UpdateEmails(typeIs("home"),
                        new EmailUpdate("ada@home2.example", null, null))).profile().emails())
                .containsExactly(WORK, new ScimEmail("ada@home2.example", "home", false));
        assertThat(fold(new UpdateEmails(typeIs("home"),
                        new EmailUpdate(null, "personal", null))).profile().emails())
                .containsExactly(WORK, new ScimEmail(HOME.value(), "personal", false));
    }

    @Test
    void promoting_a_filtered_email_to_primary_demotes_every_other() {
        assertThat(fold(new UpdateEmails(typeIs("home"), new EmailUpdate(null, null, true)))
                        .profile().emails())
                .containsExactly(
                        new ScimEmail(WORK.value(), WORK.type(), false),
                        new ScimEmail(HOME.value(), HOME.type(), true));
    }

    @Test
    void demoting_a_filtered_email_leaves_the_others_alone() {
        assertThat(fold(new UpdateEmails(typeIs("work"), new EmailUpdate(null, null, false)))
                        .profile().emails())
                .containsExactly(new ScimEmail(WORK.value(), WORK.type(), false), HOME);
    }

    @Test
    void removing_an_email_type_or_primary_clears_it_on_the_matches() {
        assertThat(fold(new RemoveEmailPart(typeIs("home"), ScimEmailPart.TYPE))
                        .profile().emails())
                .containsExactly(WORK, new ScimEmail(HOME.value(), null, false));
        assertThat(fold(new RemoveEmailPart(ScimEmailFilter.ALL, ScimEmailPart.PRIMARY))
                        .profile().emails())
                .containsExactly(new ScimEmail(WORK.value(), WORK.type(), false), HOME);
    }

    @Test
    void removing_the_required_email_value_is_a_mutability_refusal() {
        assertThatThrownBy(() -> fold(new RemoveEmailPart(typeIs("work"), ScimEmailPart.VALUE)))
                .isInstanceOfSatisfying(ScimPatchRefusedException.class, refused ->
                        assertThat(refused.reason())
                                .isEqualTo(ScimPatchRefusedException.Reason.MUTABILITY));
    }

    /** RFC 7644 §3.5.2.3: a filtered replace whose filter matches no value is noTarget. */
    @Test
    void a_filtered_update_that_selects_nothing_is_no_target() {
        assertThatThrownBy(() -> fold(new UpdateEmails(
                typeIs("pager"), new EmailUpdate("x@y.example", null, null))))
                .isInstanceOfSatisfying(ScimPatchRefusedException.class, refused ->
                        assertThat(refused.reason())
                                .isEqualTo(ScimPatchRefusedException.Reason.NO_TARGET));
    }

    /**
     * A filtered remove that selects nothing succeeds and changes nothing, as removing a
     * non-member from a Group does (RFC 7644 §3.5.2.2).
     */
    @Test
    void a_filtered_remove_that_selects_nothing_changes_nothing() {
        assertThat(fold(new RemoveEmails(typeIs("pager")))).isEqualTo(START);
        assertThat(fold(new RemoveEmailPart(typeIs("pager"), ScimEmailPart.TYPE)))
                .isEqualTo(START);
    }

    /** {@code emails.type} names no particular value, so an empty list is nothing to do. */
    @Test
    void an_unfiltered_sub_attribute_path_on_no_emails_is_not_a_no_target() {
        ScimUserEdit noEmails = fold(new RemoveEmails(ScimEmailFilter.ALL));

        assertThat(ScimUserPatchOperation.fold(noEmails, List.of(
                new RemoveEmailPart(ScimEmailFilter.ALL, ScimEmailPart.TYPE),
                new UpdateEmails(ScimEmailFilter.ALL, new EmailUpdate(null, "work", null)))))
                .isEqualTo(noEmails);
    }

    /** A sub-attribute an operation does not name is kept as stored, primacy included. */
    @Test
    void a_filtered_update_or_type_removal_keeps_the_matches_primacy() {
        assertThat(fold(new UpdateEmails(typeIs("work"),
                        new EmailUpdate("ada@work2.example", null, null))).profile().emails())
                .containsExactly(new ScimEmail("ada@work2.example", "work", true), HOME);
        assertThat(fold(new RemoveEmailPart(typeIs("work"), ScimEmailPart.TYPE))
                        .profile().emails())
                .containsExactly(new ScimEmail(WORK.value(), null, true), HOME);
    }

    // ---- the fold -------------------------------------------------------------------------

    @Test
    void operations_apply_in_order_so_a_later_one_sees_an_earlier_ones_result() {
        ScimUserEdit folded = fold(
                new SetText(TextAttribute.DISPLAY_NAME, "First"),
                new SetText(TextAttribute.DISPLAY_NAME, "Second"),
                new SetActive(false),
                new SetActive(true));

        assertThat(folded.profile().displayName()).isEqualTo("Second");
        assertThat(folded.profile().active()).isTrue();
    }

    /** Re-asserting stored values is a no-op: the result compares equal to the start. */
    @Test
    void a_sequence_that_restates_the_stored_state_folds_to_an_equal_edit() {
        assertThat(fold(
                new SetText(TextAttribute.USER_NAME, "ada"),
                new SetActive(true),
                new MergeName(NAME),
                new AddEmails(List.of(WORK)))).isEqualTo(START);
    }

    @Test
    void an_empty_filter_matches_everything_and_a_condition_matches_by_sub_attribute() {
        assertThat(ScimEmailFilter.ALL.matches(WORK)).isTrue();
        assertThat(ScimEmailFilter.ALL.selectsParticularValues()).isFalse();
        assertThat(new ScimEmailFilter(List.of(
                new ScimEmailFilter.Condition(ScimEmailPart.VALUE, "ada@work.example"),
                new ScimEmailFilter.Condition(ScimEmailPart.PRIMARY, true))).matches(WORK))
                .isTrue();
        assertThat(new ScimEmailFilter(List.of(
                new ScimEmailFilter.Condition(ScimEmailPart.VALUE, "ADA@work.example")))
                .matches(WORK)).as("value compares case-insensitively, as advertised").isTrue();
        assertThat(new ScimEmailFilter(List.of(
                new ScimEmailFilter.Condition(ScimEmailPart.VALUE, "eve@work.example")))
                .matches(WORK)).as("a different address").isFalse();
        assertThat(new ScimEmailFilter(List.of(
                new ScimEmailFilter.Condition(ScimEmailPart.VALUE, null))).matches(WORK))
                .as("every email has a value, so value eq null matches none").isFalse();
        assertThat(new ScimEmailFilter(List.of(
                new ScimEmailFilter.Condition(ScimEmailPart.TYPE, null)))
                .matches(new ScimEmail("x@y.example", null, false))).isTrue();
        assertThat(new ScimEmailFilter(List.of(
                new ScimEmailFilter.Condition(ScimEmailPart.TYPE, null))).matches(WORK))
                .isFalse();
        assertThat(new ScimEmailFilter(List.of(
                new ScimEmailFilter.Condition(ScimEmailPart.TYPE, "work")))
                .matches(new ScimEmail("x@y.example", null, false))).isFalse();
        assertThat(new ScimEmailFilter(List.of(
                new ScimEmailFilter.Condition(ScimEmailPart.PRIMARY, false))).matches(WORK))
                .isFalse();
    }
}
