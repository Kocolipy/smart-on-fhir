package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The form a Group's {@code displayName} uniqueness is decided on.
 *
 * <p>The same mapping {@link NormalizedUserName} applies, held as its own type so a Group's
 * normalized name cannot be compared against a User's without a compiler error. The rule matters
 * more here than RFC 7643 requires: this directory makes {@code displayName} unique because a
 * Group's membership confers authority, and two Groups an administrator reads as the same name is
 * how membership of the wrong one gets granted.
 */
class NormalizedDisplayNameTests {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n"})
    void a_blank_display_name_normalizes_to_nothing_and_is_refused(String blank) {
        assertThatThrownBy(() -> NormalizedDisplayName.of(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizes to nothing");
    }

    @Test
    void a_null_display_name_is_refused() {
        assertThatThrownBy(() -> NormalizedDisplayName.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizes to nothing");
    }

    @Test
    void a_blank_value_cannot_be_constructed_directly_either() {
        assertThatThrownBy(() -> new NormalizedDisplayName(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizes to nothing");
    }

    @Test
    void case_is_folded_so_two_spellings_of_one_name_collide() {
        assertThat(NormalizedDisplayName.of("Admins"))
                .isEqualTo(NormalizedDisplayName.of("ADMINS"))
                .isEqualTo(NormalizedDisplayName.of("admins"));
    }

    /**
     * Compatibility composition, so a full-width spelling cannot register a second Group that reads
     * identically to an existing one.
     */
    @Test
    void width_is_folded_by_compatibility_composition() {
        assertThat(NormalizedDisplayName.of("\uFF21\uFF44\uFF4D\uFF49\uFF4E\uFF53"))
                .isEqualTo(NormalizedDisplayName.of("admins"));
    }

    /** A decomposed spelling composes, so the two forms of an accented name are one name. */
    @Test
    void a_decomposed_spelling_composes_to_the_same_normalized_value() {
        String decomposed = Normalizer.normalize("Équipe", Normalizer.Form.NFD);

        assertThat(NormalizedDisplayName.of(decomposed))
                .isEqualTo(NormalizedDisplayName.of("Équipe"));
    }

    /**
     * Distinct names stay distinct. Without this the other assertions would all be satisfied by a
     * normalization that collapsed everything to one value.
     */
    @Test
    void distinct_names_normalize_to_distinct_values() {
        assertThat(NormalizedDisplayName.of("Admins"))
                .isNotEqualTo(NormalizedDisplayName.of("Engineering"));
    }
}
