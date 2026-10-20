package com.example.backend.patient.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Patient's id is its EHR's FHIR id, so it takes exactly FHIR's id grammar,
 * {@code [A-Za-z0-9\-.]{1,64}}, and refuses everything else without quoting it.
 */
class PatientIdTests {

    private static final String SIXTY_FOUR =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-.";

    @ParameterizedTest
    @ValueSource(strings = {
        "eq081-VQEgP8drUUqCWzHfw3", "e0w0LEDCYtfckT6N.CkJKCw3", "not-a-uuid",
        "00000000-0000-4000-8000-00000000c001", "a", "-", ".", SIXTY_FOUR})
    void anIdInTheFhirGrammarIsKeptAsItWasGiven(String value) {
        assertThat(new PatientId(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", SIXTY_FOUR + "x", "<img src=x onerror=alert(1)>", "1\r\nforged: S1234567A",
        "a//b", "a/b", "a b", "a_b", "Tan%20Wei", "é", "a\n"})
    void anIdOutsideTheFhirGrammarIsRefusedWithoutQuotingIt(String value) {
        assertThatThrownBy(() -> new PatientId(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A patient id must match the FHIR id grammar");
    }

    @Test
    void anIdIsRequired() {
        assertThatThrownBy(() -> new PatientId(null))
                .isInstanceOf(NullPointerException.class).hasMessage("value");
    }

    @Test
    void anIdPrintsAsItsValueAlone() {
        assertThat(new PatientId("eq081-VQEgP8drUUqCWzHfw3")).hasToString("eq081-VQEgP8drUUqCWzHfw3");
    }
}
