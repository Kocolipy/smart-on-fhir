package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Flow step 5: an {@code id_token}'s {@code fhirUser} names a Practitioner only in the one form
 * {@code {fhirBase}/Practitioner/{id}}, under the deployment's own FHIR base, with a non-blank
 * {@code id} and nothing after it. In the dev profile alone, the relative form
 * {@code Practitioner/{id}} the local SMART launcher issues is accepted too, under the same rules.
 */
class FhirUserReferenceTests {

    private static final URI FHIR_BASE = URI.create("https://fhir.example.org/api/FHIR/R4");

    /** Outside the dev profile: the relative form is not allowed. */
    private static final boolean STRICT = false;

    /** The dev profile: the relative form is allowed. */
    private static final boolean DEV = true;

    @Test
    void aPractitionerUnderTheConfiguredBaseYieldsItsId() {
        assertThat(FhirUserReference.practitionerId(
                "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC.123", FHIR_BASE, STRICT))
                .contains("eABC.123");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // another organisation's server
        "https://other.example.org/api/FHIR/R4/Practitioner/eABC",
        // the base as a prefix of a longer path, not the base itself
        "https://fhir.example.org/api/FHIR/R4x/Practitioner/eABC",
        // a case variant of the base: compared exactly, never normalized
        "https://FHIR.example.org/api/FHIR/R4/Practitioner/eABC"})
    void theWrongBaseIsRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, STRICT)).isEmpty();
    }

    /** The launcher's relative form names nobody outside the dev profile. */
    @Test
    void outsideTheDevProfileARelativeReferenceIsRejected() {
        assertThat(FhirUserReference.practitionerId("Practitioner/eABC", FHIR_BASE, STRICT))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://fhir.example.org/api/FHIR/R4/Patient/eABC",
        "https://fhir.example.org/api/FHIR/R4/RelatedPerson/eABC",
        "https://fhir.example.org/api/FHIR/R4/practitioner/eABC"})
    void theWrongResourceTypeIsRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, STRICT)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://fhir.example.org/api/FHIR/R4/Practitioner/",
        "https://fhir.example.org/api/FHIR/R4/Practitioner/   ",
        "https://fhir.example.org/api/FHIR/R4/Practitioner"})
    void aBlankIdIsRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, STRICT)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC/_history/2",
        "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC/",
        "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC?x=1",
        "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC#part"})
    void trailingSegmentsAreRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, STRICT)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void anAbsentClaimIsRejected(String fhirUser) {
        assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, STRICT)).isEmpty();
    }

    /** The dev profile's allowance for the local SMART launcher, and nothing more. */
    @Nested
    class InTheDevProfile {

        @Test
        void aRelativePractitionerReferenceYieldsItsId() {
            assertThat(FhirUserReference.practitionerId(
                    "Practitioner/smart-Practitioner-71482713", FHIR_BASE, DEV))
                    .contains("smart-Practitioner-71482713");
        }

        @Test
        void aPractitionerUnderTheConfiguredBaseStillYieldsItsId() {
            assertThat(FhirUserReference.practitionerId(
                    "https://fhir.example.org/api/FHIR/R4/Practitioner/eABC.123", FHIR_BASE, DEV))
                    .contains("eABC.123");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://other.example.org/api/FHIR/R4/Practitioner/eABC",
            "https://fhir.example.org/api/FHIR/R4x/Practitioner/eABC",
            // a relative reference under some other path is not the relative form
            "R4/Practitioner/eABC",
            "/Practitioner/eABC"})
        void theWrongBaseIsStillRejected(String fhirUser) {
            assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, DEV)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Patient/eABC", "RelatedPerson/eABC", "practitioner/eABC"})
        void aRelativeReferenceToAnotherResourceTypeIsRejected(String fhirUser) {
            assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, DEV)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Practitioner/", "Practitioner/   ", "Practitioner"})
        void aRelativeReferenceWithABlankIdIsRejected(String fhirUser) {
            assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, DEV)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "Practitioner/eABC/_history/2",
            "Practitioner/eABC/",
            "Practitioner/eABC?x=1",
            "Practitioner/eABC#part"})
        void aRelativeReferenceWithTrailingSegmentsIsRejected(String fhirUser) {
            assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, DEV)).isEmpty();
        }

        @ParameterizedTest
        @NullAndEmptySource
        void anAbsentClaimIsStillRejected(String fhirUser) {
            assertThat(FhirUserReference.practitionerId(fhirUser, FHIR_BASE, DEV)).isEmpty();
        }
    }
}
