package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which values carry whitespace at an edge: any Unicode {@code White_Space} character first or
 * last, the ASCII ones and the non-breaking and ideographic ones alike, while whitespace inside a
 * value and characters that merely look blank are not edge whitespace. Stripping a value removes
 * exactly that whitespace, and nothing else.
 */
class EdgeWhitespaceTests {

    @ParameterizedTest(name = "[{index}] U+{0}")
    @ValueSource(strings = {
            " x", "x ", "x\t", "\tx", "x\n", "\rx", "x\u000B", "x\f",
            // Not ASCII: NEL, no-break space, figure space, narrow no-break space, ideographic
            // space, line and paragraph separators, en quad.
            "x\u0085", " x", "x ", "x ", "　x", "x ", "x ",
            " x", " ", " "})
    void aValueBorderedByWhitespaceHasEdgeWhitespace(String value) {
        assertThat(EdgeWhitespace.borders(value)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "a b", "a\tb", "a b", "a　b", "x",
            // Zero-width space and word joiner are format characters, not White_Space.
            "x​", "⁠x",
            // Information separators are controls Java calls whitespace, but Unicode does not;
            // nor are the controls just below tab.
            "x\u001F", "\u001Cx", "x\u0008", "\u0000x", "x\u000E",
            "ada@example.org"})
    void aValueWithoutWhitespaceAtEitherEdgeHasNone(String value) {
        assertThat(EdgeWhitespace.borders(value)).isFalse();
    }

    @Test
    void anAbsentOrEmptyValueHasNone() {
        assertThat(EdgeWhitespace.borders(null)).isFalse();
        assertThat(EdgeWhitespace.borders("")).isFalse();
    }

    /** Every edge character in the set goes, from both ends, however many there are. */
    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "\"  Okta  \" | Okta",
            "\"\t\nOkta\r\" | Okta",
            // A no-break space, which String.strip() would leave, and an ideographic space.
            "\"\u00A0Okta\u3000\" | Okta",
            "\"  Epic   Hyperspace \" | Epic   Hyperspace",
            // A zero-width space is no whitespace, so it stays; nor is a character outside the
            // Basic Multilingual Plane, at either edge.
            "\"\u200BOkta\" | \u200BOkta",
            "\" \uD83D\uDE00Okta\uD83D\uDE00 \" | \uD83D\uDE00Okta\uD83D\uDE00"})
    void strippingRemovesEdgeWhitespaceOnly(String value, String stripped) {
        assertThat(EdgeWhitespace.stripped(value)).isEqualTo(stripped);
    }

    @Test
    void aValueOfWhitespaceAloneStripsToEmpty() {
        assertThat(EdgeWhitespace.stripped(" \t\u00A0\u2028 ")).isEmpty();
    }

    @Test
    void anAbsentValueStripsToAbsent() {
        assertThat(EdgeWhitespace.stripped(null)).isNull();
    }
}
