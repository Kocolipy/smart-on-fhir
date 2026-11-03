package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The two rules RFC 7643 puts on a multi-valued attribute, applied before storage. */
class ScimEmailTests {

    @Test
    void a_null_or_empty_list_canonicalizes_to_empty() {
        assertThat(ScimEmail.canonical(null)).isEmpty();
        assertThat(ScimEmail.canonical(List.of())).isEmpty();
    }

    @Test
    void a_repeated_type_and_value_is_kept_once_in_its_first_position() {
        List<ScimEmail> canonical = ScimEmail.canonical(List.of(
                new ScimEmail("bjensen@example.com", "work", false),
                new ScimEmail("home@example.com", "home", false),
                new ScimEmail("bjensen@example.com", "work", false)));

        assertThat(canonical).containsExactly(
                new ScimEmail("bjensen@example.com", "work", false),
                new ScimEmail("home@example.com", "home", false));
    }

    /** The type is a keyword, so its case does not make a second value. */
    @Test
    void a_type_differing_only_in_case_is_the_same_value() {
        assertThat(ScimEmail.canonical(List.of(
                        new ScimEmail("bjensen@example.com", "Work", false),
                        new ScimEmail("bjensen@example.com", "work", false))))
                .hasSize(1);
    }

    /**
     * So is the address: {@code emails.value} is advertised {@code caseExact=false}, as RFC 7643
     * §8.7.1 declares it, so two spellings are one value. The first spelling is the one kept.
     */
    @Test
    void an_address_differing_only_in_case_is_the_same_value_and_the_first_spelling_is_kept() {
        assertThat(ScimEmail.canonical(List.of(
                        new ScimEmail("BJensen@example.com", "work", false),
                        new ScimEmail("bjensen@example.com", "work", false))))
                .containsExactly(new ScimEmail("BJensen@example.com", "work", false));
    }

    /** A typeless address and a typed one are two values, not one. */
    @Test
    void an_absent_type_is_a_value_of_its_own() {
        assertThat(ScimEmail.canonical(List.of(
                        new ScimEmail("bjensen@example.com", null, false),
                        new ScimEmail("bjensen@example.com", "work", false))))
                .hasSize(2);
    }

    @Test
    void the_first_claim_to_primary_wins_and_the_rest_are_demoted() {
        List<ScimEmail> canonical = ScimEmail.canonical(List.of(
                new ScimEmail("first@example.com", "work", true),
                new ScimEmail("second@example.com", "home", true),
                new ScimEmail("third@example.com", "other", true)));

        assertThat(canonical).containsExactly(
                new ScimEmail("first@example.com", "work", true),
                new ScimEmail("second@example.com", "home", false),
                new ScimEmail("third@example.com", "other", false));
    }

    /**
     * A primary that is not first is still primary — the rule is "the first CLAIM wins",
     * not "the first address wins".
     *
     * <p>The distinction matters because the slot is consumed by a claim, not by a
     * position: a connector that sends a work address and then marks the home one primary
     * has stated one unambiguous preference, and demoting it would silently contradict the
     * request.
     */
    @Test
    void a_primary_that_is_not_the_first_address_keeps_its_claim() {
        List<ScimEmail> canonical = ScimEmail.canonical(List.of(
                new ScimEmail("first@example.com", "work", false),
                new ScimEmail("second@example.com", "home", true)));

        assertThat(canonical).containsExactly(
                new ScimEmail("first@example.com", "work", false),
                new ScimEmail("second@example.com", "home", true));
    }

    /** Nothing claiming primary stays that way; a primary is not invented. */
    @Test
    void no_primary_is_not_turned_into_one() {
        assertThat(ScimEmail.canonical(List.of(
                        new ScimEmail("first@example.com", "work", false),
                        new ScimEmail("second@example.com", "home", false))))
                .allSatisfy(email -> assertThat(email.primary()).isFalse());
    }

    /**
     * De-duplication runs before the primary rule, so a duplicate that claimed primary
     * does not consume the one primary slot on its way out.
     */
    @Test
    void a_removed_duplicate_does_not_consume_the_primary_slot() {
        List<ScimEmail> canonical = ScimEmail.canonical(List.of(
                new ScimEmail("dup@example.com", "work", false),
                new ScimEmail("dup@example.com", "work", true),
                new ScimEmail("real@example.com", "home", true)));

        assertThat(canonical).containsExactly(
                new ScimEmail("dup@example.com", "work", false),
                new ScimEmail("real@example.com", "home", true));
    }
}
