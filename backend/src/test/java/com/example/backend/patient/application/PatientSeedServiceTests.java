package com.example.backend.patient.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.patient.InMemoryPatientStore;
import com.example.backend.patient.application.PatientSeedService.SeedCounts;
import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientName;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The development patient fixtures' seeding: idempotent by fixed id, inserting only what is missing. */
class PatientSeedServiceTests {

    private static final Patient FIRST = new Patient(
            UUID.fromString("0b7d8c57-3f5e-4c39-a7e6-1f0f8d0e2a11"), "S1111111A",
            new PatientName("Lim Mei Ling", null, List.of()),
            AdministrativeGender.FEMALE, LocalDate.of(1970, 1, 2));

    private static final Patient SECOND = new Patient(
            UUID.fromString("0b7d8c57-3f5e-4c39-a7e6-1f0f8d0e2a12"), "S2222222A",
            new PatientName("Ahmad bin Ismail", null, List.of()),
            AdministrativeGender.MALE, LocalDate.of(1980, 5, 6));

    private final InMemoryPatientStore patients = new InMemoryPatientStore();

    private final PatientSeedService seeding = new PatientSeedService(patients);

    @Test
    void anEmptyStoreGetsEveryFixture() {
        SeedCounts counts = seeding.seedDevFixtures(List.of(FIRST, SECOND));

        assertThat(patients.all()).containsExactly(FIRST, SECOND);
        assertThat(counts).isEqualTo(new SeedCounts(2, 0));
    }

    @Test
    void seedingTwiceLeavesOnePatientPerFixture() {
        seeding.seedDevFixtures(List.of(FIRST, SECOND));

        SeedCounts again = seeding.seedDevFixtures(List.of(FIRST, SECOND));

        assertThat(patients.all()).containsExactly(FIRST, SECOND);
        assertThat(again).isEqualTo(new SeedCounts(0, 2));
    }

    @Test
    void onlyTheMissingFixtureIsInsertedAndTheOtherIsLeftAsItIs() {
        // Stored under the fixture's id, but changed since: seeding never overwrites.
        Patient changed = new Patient(FIRST.id(), "S9999999A",
                new PatientName("Changed", null, List.of()), AdministrativeGender.OTHER, LocalDate.of(2000, 1, 1));
        patients.given(changed);

        SeedCounts counts = seeding.seedDevFixtures(List.of(FIRST, SECOND));

        assertThat(patients.all()).containsExactly(changed, SECOND);
        assertThat(counts).isEqualTo(new SeedCounts(1, 1));
    }

    @Test
    void noFixturesInsertNothing() {
        assertThat(seeding.seedDevFixtures(List.of())).isEqualTo(new SeedCounts(0, 0));
        assertThat(patients.all()).isEmpty();
    }

    @Test
    void theDefaultPatientIsAFixtureUnderItsFixedId() {
        assertThat(DevPatientFixtures.ALL).extracting(Patient::id).containsExactly(
                UUID.fromString("00000000-0000-4000-8000-00000000c001"));
    }

    @Test
    void theDefaultPatientIsTanWeiMingFamilyNameFirst() {
        assertThat(DevPatientFixtures.DEFAULT_PATIENT).isEqualTo(new Patient(
                UUID.fromString("00000000-0000-4000-8000-00000000c001"),
                "S1234567A",
                new PatientName("Tan Wei Ming", "Tan", List.of("Wei", "Ming")),
                AdministrativeGender.MALE,
                LocalDate.of(1985, 3, 12)));
    }

    /**
     * The NRIC's check letter, by the published weights 2, 7, 6, 5, 4, 3, 2 and the S-series
     * letters J Z I H G F E D C B A: S1234567's is D. The fixture's A is wrong on purpose, so
     * the dummy can never be a real person's NRIC.
     */
    @Test
    void theDefaultPatientsNricHasAWrongCheckLetter() {
        String nric = DevPatientFixtures.DEFAULT_PATIENT.nric();
        int[] weights = {2, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (nric.charAt(i + 1) - '0') * weights[i];
        }
        char valid = "JZIHGFEDCBA".charAt(sum % 11);

        assertThat(valid).isEqualTo('D');
        assertThat(nric.charAt(8)).isNotEqualTo(valid);
    }
}
