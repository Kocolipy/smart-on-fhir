package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** What two {@code userName}s have to have in common to be the same {@code userName}. */
class NormalizedUserNameTests {

    @Test
    void case_does_not_distinguish_two_user_names() {
        assertThat(NormalizedUserName.of("BJensen"))
                .isEqualTo(NormalizedUserName.of("bjensen"));
    }

    /**
     * NFKC folds a full-width spelling onto the ASCII one, so a directory cannot hold
     * {@code ｂｊｅｎｓｅｎ} beside {@code bjensen} — which would be two identities a human
     * reader cannot tell apart.
     */
    @Test
    void a_full_width_spelling_is_the_same_name() {
        assertThat(NormalizedUserName.of("\uFF42\uFF4A\uFF45\uFF4E\uFF53\uFF45\uFF4E"))
                .isEqualTo(NormalizedUserName.of("bjensen"));
    }

    /** And a decomposed one: {@code e} plus a combining acute is the same as {@code é}. */
    @Test
    void a_decomposed_spelling_is_the_same_name() {
        assertThat(NormalizedUserName.of("jos\u0065\u0301"))
                .isEqualTo(NormalizedUserName.of("jos\u00e9"));
    }

    /**
     * Case folding is locale-independent. Under a Turkish default locale
     * {@code "I".toLowerCase()} is a dotless {@code ı}, which would make this service's
     * uniqueness rule depend on the host's configuration.
     */
    @Test
    void case_folding_does_not_depend_on_the_hosts_locale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(NormalizedUserName.of("INGRID").value()).isEqualTo("ingrid");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void a_name_that_normalizes_to_nothing_is_refused() {
        assertThatThrownBy(() -> NormalizedUserName.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NormalizedUserName.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Two different names stay different; folding must not collapse everything. */
    @Test
    void unrelated_names_remain_distinct() {
        assertThat(NormalizedUserName.of("bjensen"))
                .isNotEqualTo(NormalizedUserName.of("bjenson"));
    }

    /**
     * The record's own guard, reached by constructing one directly rather than through
     * {@link NormalizedUserName#of}. The type is the thing uniqueness is decided on, so a
     * caller that builds one from a stored value must not be able to hold an empty one.
     */
    @Test
    void a_directly_constructed_empty_name_is_refused() {
        assertThatThrownBy(() -> new NormalizedUserName(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizes to nothing");
        assertThatThrownBy(() -> new NormalizedUserName("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizes to nothing");
    }
}
