package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The attributes a connector writes, and the two the record normalizes on the way in. */
class ScimUserProfileTests {

    private static ScimUserProfile profile(String userName) {
        return new ScimUserProfile(
                userName, null, null, null, null, null, true, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n"})
    void a_blank_user_name_is_refused(String blank) {
        assertThatThrownBy(() -> profile(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has a userName");
    }

    @Test
    void a_null_user_name_is_refused() {
        assertThatThrownBy(() -> profile(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has a userName");
    }

    @Test
    void a_null_name_becomes_the_absent_name_rather_than_staying_null() {
        assertThat(profile("bjensen").name()).isEqualTo(ScimName.NONE);
    }

    @Test
    void the_profile_keeps_the_attributes_it_was_given() {
        ScimName name = new ScimName("Barbara Jensen", "Jensen", "Barbara", null, null, null);
        ScimUserProfile profile = new ScimUserProfile(
                "bjensen", name, "Babs", "en-US", "en-GB", "Europe/London", true, null);

        assertThat(profile)
                .extracting(
                        ScimUserProfile::name,
                        ScimUserProfile::displayName,
                        ScimUserProfile::preferredLanguage,
                        ScimUserProfile::locale,
                        ScimUserProfile::timezone,
                        ScimUserProfile::active)
                .containsExactly(name, "Babs", "en-US", "en-GB", "Europe/London", true);
    }

    @Test
    void null_emails_become_an_empty_list_rather_than_staying_null() {
        assertThat(profile("bjensen").emails()).isEmpty();
    }

    @Test
    void the_emails_are_canonicalized_on_the_way_in() {
        ScimUserProfile profile = new ScimUserProfile(
                "bjensen", null, null, null, null, null, true,
                List.of(
                        new ScimEmail("work@example.com", "work", true),
                        new ScimEmail("work@example.com", "work", true),
                        new ScimEmail("home@example.com", "home", true)));

        assertThat(profile.emails()).containsExactly(
                new ScimEmail("work@example.com", "work", true),
                new ScimEmail("home@example.com", "home", false));
    }

    @Test
    void the_uniqueness_form_is_the_normalized_user_name() {
        assertThat(profile("BJensen").normalizedUserName())
                .isEqualTo(NormalizedUserName.of("bjensen"));
    }

    @Test
    void a_group_shows_a_member_by_its_display_name_when_it_has_one() {
        ScimUserProfile profile = new ScimUserProfile(
                "bjensen", null, "Barbara Jensen", null, null, null, true, null);

        assertThat(profile.memberLabel()).isEqualTo("Barbara Jensen");
    }

    @Test
    void a_group_shows_a_member_without_a_display_name_by_its_user_name() {
        assertThat(profile("bjensen").memberLabel()).isEqualTo("bjensen");
    }

    @Test
    void the_member_label_from_bare_attributes_prefers_the_display_name() {
        assertThat(ScimUserProfile.memberLabel("Barbara Jensen", "bjensen"))
                .isEqualTo("Barbara Jensen");
    }

    @Test
    void the_member_label_from_bare_attributes_falls_back_to_the_user_name() {
        assertThat(ScimUserProfile.memberLabel(null, "bjensen")).isEqualTo("bjensen");
    }
}
