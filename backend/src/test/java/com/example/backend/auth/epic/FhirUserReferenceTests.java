package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Flow step 5: an {@code id_token}'s {@code fhirUser} names a Practitioner only in the one form
 * {@code {fhirBase}/Practitioner/{id}}, under the deployment's own FHIR base, with a non-blank
 * {@code id} and nothing after it.
 */
class FhirUserReferenceTests {

    private static final URI FHIR_BASE = URI.create("https://fhir.example.org/api/FHIR/R4");

    @Test
    void aPractitionerUnderTheConfiguredBaseYieldsItsId() {
        assertThat(FhirUserReference.practitionerId(
                "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC.123", FHIR_BASE))
                .contains("eABC.123");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // another organisation's server
        "https://other.example.org/api/FHIR/R4/Practitioner/eABC",
        // the base as a prefix of a longer path, not the base itself
        "https://fhir.example.org/api/FHIR/R4x/Practitioner/eABC",
        // a case variant of the base: compared exactly, never normalized
        "https://FHIR.example.org/api/FHIR/R4/Practitioner/eABC",
        // a relative reference
        "Practitioner/eABC"})
    void theWrongBaseIsRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://fhir.example.org/api/FHIR/R4/Patient/eABC",
        "https://fhir.example.org/api/FHIR/R4/RelatedPerson/eABC",
        "https://fhir.example.org/api/FHIR/R4/practitioner/eABC"})
    void theWrongResourceTypeIsRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://fhir.example.org/api/FHIR/R4/Practitioner/",
        "https://fhir.example.org/api/FHIR/R4/Practitioner/   ",
        "https://fhir.example.org/api/FHIR/R4/Practitioner"})
    void aBlankIdIsRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC/_history/2",
        "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC/",
        "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC?x=1",
        "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC#part"})
    void trailingSegmentsAreRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void anAbsentClaimIsRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE)).isEmpty();
    }
}
