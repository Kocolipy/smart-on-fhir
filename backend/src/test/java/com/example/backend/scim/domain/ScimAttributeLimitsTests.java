package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.ScimValueControlCharacterException.Forbidden;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every stored User and Group attribute is held to its column's width, counted the way the
 * column counts, and a refusal names the attribute and the limit but never carries the value.
 */
class ScimAttributeLimitsTests {

    /** A character outside the Basic Multilingual Plane: one code point, two UTF-16 units. */
    private static final String SUPPLEMENTARY = "\uD83D\uDE00";

    /** U+FDFA, a single code point NFKC expands into eighteen. */
    private static final String EXPANDS_UNDER_NFKC = "\uFDFA";

    private static final ScimUserProfile WITHIN = new ScimUserProfile(
            "ada",
            new ScimName("Ada King", "King", "Ada", "Augusta", "Hon.", "Countess"),
            "Ada",
            "en",
            "en-GB",
            "Europe/London",
            true,
            List.of(new ScimEmail("ada@work.example", "work", true)));

    /** One per stored User attribute: the path a refusal names, its limit, and a profile with it. */
    static Stream<Arguments> userAttributes() {
        return Stream.of(
                user("userName", 256, v -> profile(v, ScimName.NONE, null, null, null, null,
                        List.of())),
                user("name.formatted", 256, v -> named(new ScimName(v, null, null, null, null,
                        null))),
                user("name.familyName", 256, v -> named(new ScimName(null, v, null, null, null,
                        null))),
                user("name.givenName", 256, v -> named(new ScimName(null, null, v, null, null,
                        null))),
                user("name.middleName", 256, v -> named(new ScimName(null, null, null, v, null,
                        null))),
                user("name.honorificPrefix", 256, v -> named(new ScimName(null, null, null, null,
                        v, null))),
                user("name.honorificSuffix", 256, v -> named(new ScimName(null, null, null, null,
                        null, v))),
                user("displayName", 256, v -> profile("ada", ScimName.NONE, v, null, null, null,
                        List.of())),
                user("preferredLanguage", 64, v -> profile("ada", ScimName.NONE, null, v, null,
                        null, List.of())),
                user("locale", 64, v -> profile("ada", ScimName.NONE, null, null, v, null,
                        List.of())),
                user("timezone", 64, v -> profile("ada", ScimName.NONE, null, null, null, v,
                        List.of())),
                user("emails.value", 256, v -> profile("ada", ScimName.NONE, null, null, null,
                        null, List.of(new ScimEmail(v, "work", false)))),
                user("emails.type", 32, v -> profile("ada", ScimName.NONE, null, null, null, null,
                        List.of(new ScimEmail("ada@work.example", v, false)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("userAttributes")
    void a_user_value_at_its_limit_is_accepted_and_one_more_is_refused_naming_it(
            String attribute, int limit, Function<String, ScimUserProfile> withValue) {
        assertThatCode(() -> ScimAttributeLimits.requireWithin(withValue.apply("a".repeat(limit))))
                .doesNotThrowAnyException();

        String tooLong = "b".repeat(limit + 1);
        assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(withValue.apply(tooLong)))
                .isInstanceOfSatisfying(ScimValueTooLongException.class, refused -> {
                    assertThat(refused.attribute()).isEqualTo(attribute);
                    assertThat(refused.limit()).isEqualTo(limit);
                    assertThat(refused.getMessage()).doesNotContain(tooLong);
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("userAttributes")
    void a_user_value_is_counted_in_code_points_as_the_column_counts_it(
            String attribute, int limit, Function<String, ScimUserProfile> withValue) {
        // Twice `limit` UTF-16 units, exactly `limit` characters: the column stores it.
        assertThatCode(() -> ScimAttributeLimits.requireWithin(
                withValue.apply(SUPPLEMENTARY.repeat(limit))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(
                withValue.apply(SUPPLEMENTARY.repeat(limit + 1))))
                .isInstanceOf(ScimValueTooLongException.class);
    }

    /** The names shown to people and written to logs: they refuse every control, not only NUL. */
    private static final Set<String> NAMES = Set.of("userName", "displayName");

    /**
     * Every stored User string refuses U+0000, wherever in the value it is, naming the attribute
     * and never carrying the value — and a name says it refuses every control.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("userAttributes")
    void a_user_value_holding_a_nul_is_refused_naming_it(
            String attribute, int limit, Function<String, ScimUserProfile> withValue) {
        Forbidden expected = NAMES.contains(attribute) ? Forbidden.CONTROL : Forbidden.NUL;
        for (String holding : List.of("a\0b", "\0", "ab\0")) {
            assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(withValue.apply(holding)))
                    .isInstanceOfSatisfying(ScimValueControlCharacterException.class, refused -> {
                        assertThat(refused.attribute()).isEqualTo(attribute);
                        assertThat(refused.forbidden()).isEqualTo(expected);
                        assertThat(refused.getMessage()).isEqualTo(
                                attribute + " must not contain " + expected.description() + ".");
                    });
        }
    }

    /**
     * The other C0 controls and DEL: refused in a name, at both ends of the range, and kept
     * everywhere else, since the database stores them and a formatted name may carry a tab.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("userAttributes")
    void a_control_other_than_nul_is_refused_only_in_a_name(
            String attribute, int limit, Function<String, ScimUserProfile> withValue) {
        for (String holding : List.of("a\u0001b", "a\tb", "a\nb", "a\u001Fb", "a\u007Fb")) {
            if (NAMES.contains(attribute)) {
                assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(withValue.apply(holding)))
                        .as(holding)
                        .isInstanceOfSatisfying(ScimValueControlCharacterException.class, refused -> {
                            assertThat(refused.attribute()).isEqualTo(attribute);
                            assertThat(refused.forbidden()).isEqualTo(Forbidden.CONTROL);
                        });
            } else {
                assertThatCode(() -> ScimAttributeLimits.requireWithin(withValue.apply(holding)))
                        .as(holding)
                        .doesNotThrowAnyException();
            }
        }
        // The neighbours of the refused range are ordinary characters, in a name too.
        for (String neighbour : List.of("a b", "a~b", "a\u0080b", "a\u00A0b")) {
            assertThatCode(() -> ScimAttributeLimits.requireWithin(withValue.apply(neighbour)))
                    .as(neighbour)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * The User attributes that are unique or looked up — the keys SQL Server would compare with
     * trailing spaces ignored (ADR 0015) — and so refuse whitespace at either edge.
     */
    private static final Set<String> KEYS = Set.of("userName", "displayName", "emails.value",
            "emails.type");

    /**
     * A key refuses a value beginning or ending with whitespace, naming the attribute and never
     * the value; every other attribute keeps the whitespace it was sent. Whitespace other than a
     * control is used so a name is refused for the edge, not for the control it would also hold.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("userAttributes")
    void a_key_bordered_by_whitespace_is_refused_and_any_other_value_keeps_it(
            String attribute, int limit, Function<String, ScimUserProfile> withValue) {
        for (String bordered : List.of(" x", "x ", " x", "x　")) {
            if (KEYS.contains(attribute)) {
                assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(withValue.apply(bordered)))
                        .as(bordered)
                        .isInstanceOfSatisfying(ScimValueEdgeWhitespaceException.class, refused -> {
                            assertThat(refused.attribute()).isEqualTo(attribute);
                            assertThat(refused.getMessage()).isEqualTo(
                                    attribute + " must not begin or end with whitespace.");
                        });
            } else {
                assertThatCode(() -> ScimAttributeLimits.requireWithin(withValue.apply(bordered)))
                        .as(bordered)
                        .doesNotThrowAnyException();
            }
        }
    }

    /** A trailing tab is refused on every key: as a control in a name, as whitespace elsewhere. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("userAttributes")
    void a_trailing_tab_is_refused_on_every_key(
            String attribute, int limit, Function<String, ScimUserProfile> withValue) {
        if (KEYS.contains(attribute)) {
            assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(withValue.apply("x\t")))
                    .isInstanceOfSatisfying(ScimAttributeValueException.class,
                            refused -> assertThat(refused.attribute()).isEqualTo(attribute));
        } else {
            assertThatCode(() -> ScimAttributeLimits.requireWithin(withValue.apply("x\t")))
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("userAttributes")
    void whitespace_inside_a_value_is_accepted_on_every_attribute(
            String attribute, int limit, Function<String, ScimUserProfile> withValue) {
        assertThatCode(() -> ScimAttributeLimits.requireWithin(withValue.apply("a b")))
                .doesNotThrowAnyException();
    }

    @Test
    void a_group_display_name_bordered_by_whitespace_is_refused() {
        for (String bordered : List.of(" g", "g ", " g", "g　")) {
            assertThatThrownBy(() -> ScimAttributeLimits.requireGroupDisplayNameWithin(bordered))
                    .as(bordered)
                    .isInstanceOfSatisfying(ScimValueEdgeWhitespaceException.class, refused -> {
                        assertThat(refused.attribute()).isEqualTo("displayName");
                        assertThat(refused.getMessage())
                                .isEqualTo("displayName must not begin or end with whitespace.");
                    });
        }
        assertThatCode(() -> ScimAttributeLimits.requireGroupDisplayNameWithin("g h"))
                .doesNotThrowAnyException();
    }

    @Test
    void an_external_id_bordered_by_whitespace_is_refused() {
        for (String bordered : List.of(" x", "x ", "x\t", " x")) {
            assertThatThrownBy(() -> ScimAttributeLimits.requireExternalIdWithin(bordered))
                    .as(bordered)
                    .isInstanceOfSatisfying(ScimValueEdgeWhitespaceException.class, refused -> {
                        assertThat(refused.attribute()).isEqualTo("externalId");
                        assertThat(refused.getMessage())
                                .isEqualTo("externalId must not begin or end with whitespace.");
                    });
        }
        assertThatCode(() -> ScimAttributeLimits.requireExternalIdWithin("x y"))
                .doesNotThrowAnyException();
    }

    /** Every email is held to the rule, not only the first. */
    @Test
    void edge_whitespace_in_a_later_email_is_refused() {
        ScimUserProfile secondBordered = profile("ada", ScimName.NONE, null, null, null, null,
                List.of(new ScimEmail("ada@work.example", "work", true),
                        new ScimEmail("ada@home.example ", "home", false)));

        assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(secondBordered))
                .isInstanceOfSatisfying(ScimValueEdgeWhitespaceException.class,
                        refused -> assertThat(refused.attribute()).isEqualTo("emails.value"));
    }

    /** Characters are checked before length, so a long value with a NUL is named for the NUL. */
    @Test
    void a_value_both_too_long_and_holding_a_nul_is_refused_for_the_nul() {
        assertThatThrownBy(() -> ScimAttributeLimits.requireExternalIdWithin(
                "x".repeat(300) + "\0"))
                .isInstanceOf(ScimValueControlCharacterException.class);
    }

    @Test
    void a_group_display_name_refuses_every_control_and_an_external_id_refuses_nul() {
        for (String holding : List.of("g\0", "g\u0001", "g\n", "g\u001F", "g\u007F")) {
            assertThatThrownBy(() -> ScimAttributeLimits.requireGroupDisplayNameWithin(holding))
                    .isInstanceOfSatisfying(ScimValueControlCharacterException.class, refused -> {
                        assertThat(refused.attribute()).isEqualTo("displayName");
                        assertThat(refused.forbidden()).isEqualTo(Forbidden.CONTROL);
                        assertThat(refused.getMessage()).isEqualTo("displayName must not contain"
                                + " control characters (U+0000 to U+001F, U+007F).");
                    });
        }
        assertThatCode(() -> ScimAttributeLimits.requireGroupDisplayNameWithin("g \u0080"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> ScimAttributeLimits.requireExternalIdWithin("x\0y"))
                .isInstanceOfSatisfying(ScimValueControlCharacterException.class, refused -> {
                    assertThat(refused.attribute()).isEqualTo("externalId");
                    assertThat(refused.forbidden()).isEqualTo(Forbidden.NUL);
                    assertThat(refused.getMessage())
                            .isEqualTo("externalId must not contain the NUL character (U+0000).");
                });
        assertThatCode(() -> ScimAttributeLimits.requireExternalIdWithin("x\ty\u001F"))
                .doesNotThrowAnyException();
    }

    /** Every email is checked for a NUL, not only the first. */
    @Test
    void a_nul_in_a_later_email_is_refused() {
        ScimUserProfile secondHoldsNul = profile("ada", ScimName.NONE, null, null, null, null,
                List.of(new ScimEmail("ada@work.example", "work", true),
                        new ScimEmail("ada@home.example", "ho\0me", false)));

        assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(secondHoldsNul))
                .isInstanceOfSatisfying(ScimValueControlCharacterException.class,
                        refused -> assertThat(refused.attribute()).isEqualTo("emails.type"));
    }

    @Test
    void a_profile_within_every_limit_is_accepted() {
        assertThatCode(() -> ScimAttributeLimits.requireWithin(WITHIN)).doesNotThrowAnyException();
    }

    @Test
    void every_email_is_checked_not_only_the_first() {
        ScimUserProfile secondTooLong = profile("ada", ScimName.NONE, null, null, null, null,
                List.of(new ScimEmail("ada@work.example", "work", true),
                        new ScimEmail("ada@home.example", "t".repeat(33), false)));

        assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(secondTooLong))
                .isInstanceOfSatisfying(ScimValueTooLongException.class,
                        refused -> assertThat(refused.attribute()).isEqualTo("emails.type"));
    }

    /**
     * The normalized form is a column of the same width, and NFKC can lengthen a name: fifteen
     * copies of U+FDFA are fifteen characters submitted and 270 normalized, fourteen are 252.
     */
    @Test
    void a_user_name_that_fits_but_normalizes_past_the_limit_is_refused() {
        String fits = EXPANDS_UNDER_NFKC.repeat(15);
        assertThat(fits.codePointCount(0, fits.length())).isEqualTo(15);
        assertThat(NormalizedUserName.of(fits).value().length()).isGreaterThan(256);

        assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(
                profile(fits, ScimName.NONE, null, null, null, null, List.of())))
                .isInstanceOfSatisfying(ScimValueTooLongException.class, refused -> {
                    assertThat(refused.attribute()).isEqualTo("userName");
                    assertThat(refused.limit()).isEqualTo(256);
                });
        assertThatCode(() -> ScimAttributeLimits.requireWithin(profile(
                EXPANDS_UNDER_NFKC.repeat(14), ScimName.NONE, null, null, null, null, List.of())))
                .doesNotThrowAnyException();
    }

    /**
     * The converse: the submitted form is stored too, so a name that NFKC would shorten to fit is
     * still refused when it is too long as sent. {@code e} plus a combining acute is two code
     * points that compose into one.
     */
    @Test
    void a_name_too_long_as_submitted_is_refused_even_though_it_normalizes_to_fit() {
        String shrinks = "e\u0301".repeat(200);
        assertThat(shrinks.codePointCount(0, shrinks.length())).isEqualTo(400);
        assertThat(NormalizedUserName.of(shrinks).value().length()).isEqualTo(200);

        assertThatThrownBy(() -> ScimAttributeLimits.requireWithin(
                profile(shrinks, ScimName.NONE, null, null, null, null, List.of())))
                .isInstanceOfSatisfying(ScimValueTooLongException.class,
                        refused -> assertThat(refused.attribute()).isEqualTo("userName"));
        assertThatThrownBy(() -> ScimAttributeLimits.requireGroupDisplayNameWithin(shrinks))
                .isInstanceOfSatisfying(ScimValueTooLongException.class,
                        refused -> assertThat(refused.attribute()).isEqualTo("displayName"));
    }

    @Test
    void a_group_display_name_is_held_to_256_submitted_and_normalized() {
        assertThatCode(() -> ScimAttributeLimits.requireGroupDisplayNameWithin("g".repeat(256)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScimAttributeLimits.requireGroupDisplayNameWithin(
                SUPPLEMENTARY.repeat(256)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScimAttributeLimits.requireGroupDisplayNameWithin(
                "g".repeat(257)))
                .isInstanceOfSatisfying(ScimValueTooLongException.class, refused -> {
                    assertThat(refused.attribute()).isEqualTo("displayName");
                    assertThat(refused.limit()).isEqualTo(256);
                });
        assertThatThrownBy(() -> ScimAttributeLimits.requireGroupDisplayNameWithin(
                EXPANDS_UNDER_NFKC.repeat(15)))
                .isInstanceOfSatisfying(ScimValueTooLongException.class,
                        refused -> assertThat(refused.attribute()).isEqualTo("displayName"));
    }

    @Test
    void an_external_id_is_held_to_256_and_an_absent_one_is_within() {
        assertThatCode(() -> ScimAttributeLimits.requireExternalIdWithin(null))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScimAttributeLimits.requireExternalIdWithin("x".repeat(256)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScimAttributeLimits.requireExternalIdWithin(
                SUPPLEMENTARY.repeat(256)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScimAttributeLimits.requireExternalIdWithin("x".repeat(257)))
                .isInstanceOfSatisfying(ScimValueTooLongException.class, refused -> {
                    assertThat(refused.attribute()).isEqualTo("externalId");
                    assertThat(refused.limit()).isEqualTo(256);
                    assertThat(refused.getMessage())
                            .isEqualTo("externalId is longer than 256 characters.");
                });
    }

    private static Arguments user(
            String attribute, int limit, Function<String, ScimUserProfile> withValue) {
        return Arguments.of(attribute, limit, withValue);
    }

    private static ScimUserProfile named(ScimName name) {
        return profile("ada", name, null, null, null, null, List.of());
    }

    private static ScimUserProfile profile(
            String userName,
            ScimName name,
            String displayName,
            String preferredLanguage,
            String locale,
            String timezone,
            List<ScimEmail> emails) {
        return new ScimUserProfile(
                userName, name, displayName, preferredLanguage, locale, timezone, true, emails);
    }
}
