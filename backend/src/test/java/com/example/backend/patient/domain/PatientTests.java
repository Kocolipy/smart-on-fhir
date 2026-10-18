package com.example.backend.patient.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a Patient must have: every element but the name's family and given parts. */
class PatientTests {

    private static final UUID ID = UUID.fromString("0b7d8c57-3f5e-4c39-a7e6-1f0f8d0e2a11");
    private static final PatientName NAME = PatientName.of("Tan Wei Ming");
    private static final LocalDate BORN = LocalDate.of(1985, 3, 12);

    @Test
    void aPatientNeedsAnId() {
        assertThatThrownBy(() -> new Patient(null, "S1234567A", NAME, AdministrativeGender.MALE, BORN))
                .isInstanceOf(NullPointerException.class).hasMessage("id");
    }

    @Test
    void aPatientNeedsAnNric() {
        assertThatThrownBy(() -> new Patient(ID, null, NAME, AdministrativeGender.MALE, BORN))
                .isInstanceOf(NullPointerException.class).hasMessage("nric");
    }

    @Test
    void aPatientNeedsAName() {
        assertThatThrownBy(() -> new Patient(ID, "S1234567A", null, AdministrativeGender.MALE, BORN))
                .isInstanceOf(NullPointerException.class).hasMessage("name");
    }

    @Test
    void aPatientNeedsAGender() {
        assertThatThrownBy(() -> new Patient(ID, "S1234567A", NAME, null, BORN))
                .isInstanceOf(NullPointerException.class).hasMessage("gender");
    }

    @Test
    void aPatientNeedsABirthDate() {
        assertThatThrownBy(() -> new Patient(ID, "S1234567A", NAME, AdministrativeGender.MALE, null))
                .isInstanceOf(NullPointerException.class).hasMessage("birthDate");
    }

    @Test
    void aNameNeedsItsText() {
        assertThatThrownBy(() -> new PatientName(null, "Tan", List.of("Wei")))
                .isInstanceOf(NullPointerException.class).hasMessage("text");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void aNameTextCannotBeBlank(String blank) {
        assertThatThrownBy(() -> PatientName.of(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A patient's name text is required");
    }

    @Test
    void aNameGivenByItsTextAloneHasNoFamilyOrGivenPart() {
        assertThat(PatientName.of("Suriani")).isEqualTo(new PatientName("Suriani", null, List.of()));
    }

    @Test
    void aNameWithNoGivenListHasAnEmptyOne() {
        assertThat(new PatientName("Suriani", null, null).given()).isEmpty();
    }

    @Test
    void aNameKeepsItsGivenNamesWhateverTheCallerDoesToItsList() {
        List<String> given = new ArrayList<>(List.of("Wei", "Ming"));
        PatientName name = new PatientName("Tan Wei Ming", "Tan", given);

        given.clear();

        assertThat(name.given()).containsExactly("Wei", "Ming");
    }

    @ParameterizedTest
    @ValueSource(strings = {"male", "female", "other", "unknown"})
    void eachFhirGenderCodeNamesItsGender(String code) {
        assertThat(AdministrativeGender.fromCode(code)).map(AdministrativeGender::code).contains(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Male", "M", "", "intersex"})
    void anythingElseNamesNoGender(String code) {
        assertThat(AdministrativeGender.fromCode(code)).isEmpty();
    }
}
