package com.example.backend.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * D18's bounds on {@code launch} and {@code code} — 1–8192 characters of printable ASCII, no
 * whitespace — and D10's exact {@code iss}, each answered with the rule broken.
 */
class EpicInputBoundsTests {

    private static final String FHIR_BASE = "https://fhir.example.org/api/FHIR/R4";

    @Test
    void anAbsentValueIsMissing() {
        assertThat(EpicInputBounds.brokenBy(null)).contains(EpicInputRule.MISSING);
    }

    @Test
    void anEmptyValueIsMissing() {
        assertThat(EpicInputBounds.brokenBy("")).contains(EpicInputRule.MISSING);
    }

    @Test
    void aSingleCharacterIsWithinBounds() {
        assertThat(EpicInputBounds.brokenBy("x")).isEmpty();
    }

    @Test
    void exactly8192CharactersIsWithinBounds() {
        assertThat(EpicInputBounds.brokenBy("x".repeat(8192))).isEmpty();
    }

    @Test
    void characters8193BreakTheLength() {
        assertThat(EpicInputBounds.brokenBy("x".repeat(8193))).contains(EpicInputRule.LENGTH);
    }

    /** Every printable ASCII character, {@code !} through {@code ~}, is allowed. */
    @Test
    void everyPrintableAsciiCharacterIsWithinBounds() {
        StringBuilder printable = new StringBuilder();
        for (char c = '!'; c <= '~'; c++) {
            printable.append(c);
        }

        assertThat(EpicInputBounds.brokenBy(printable.toString())).isEmpty();
    }

    @Test
    void aSpaceBreaksTheCharset() {
        assertThat(EpicInputBounds.brokenBy("a b")).contains(EpicInputRule.CHARSET);
    }

    @Test
    void aControlCharacterBreaksTheCharset() {
        assertThat(EpicInputBounds.brokenBy("a\u001fb")).contains(EpicInputRule.CHARSET);
    }

    @Test
    void deleteBreaksTheCharset() {
        assertThat(EpicInputBounds.brokenBy("a\u007fb")).contains(EpicInputRule.CHARSET);
    }

    @Test
    void aNonAsciiCharacterBreaksTheCharset() {
        assertThat(EpicInputBounds.brokenBy("café")).contains(EpicInputRule.CHARSET);
    }

    /** Length is checked first: an over-long value is refused for that, whatever it holds. */
    @Test
    void anOverlongValueWithWhitespaceBreaksTheLength() {
        assertThat(EpicInputBounds.brokenBy(" ".repeat(8193))).contains(EpicInputRule.LENGTH);
    }

    @Test
    void anIssExactlyTheFhirBaseIsAccepted() {
        assertThat(EpicInputBounds.issBrokenBy(FHIR_BASE, FHIR_BASE)).isEmpty();
    }

    @Test
    void anAbsentIssIsMissing() {
        assertThat(EpicInputBounds.issBrokenBy(null, FHIR_BASE)).contains(EpicInputRule.MISSING);
    }

    @Test
    void anEmptyIssIsMissing() {
        assertThat(EpicInputBounds.issBrokenBy("", FHIR_BASE)).contains(EpicInputRule.MISSING);
    }

    /** D10: never normalized — no case folding. */
    @Test
    void aCaseVariantIssIsAMismatch() {
        assertThat(EpicInputBounds.issBrokenBy(FHIR_BASE.toUpperCase(), FHIR_BASE))
                .contains(EpicInputRule.MISMATCH);
    }

    /** D10: never normalized — no trailing-slash trimming. */
    @Test
    void anIssWithATrailingSlashIsAMismatch() {
        assertThat(EpicInputBounds.issBrokenBy(FHIR_BASE + "/", FHIR_BASE))
                .contains(EpicInputRule.MISMATCH);
    }
}
