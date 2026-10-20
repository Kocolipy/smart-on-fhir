package com.example.backend.patient.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.patient.InMemoryPatientStore;
import com.example.backend.patient.application.PatientSeedService.SeedCounts;
import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientName;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The development patient fixtures' seeding: idempotent by fixed id, inserting only what is missing. */
class PatientSeedServiceTests {

    private static final Patient FIRST = new Patient(
            new PatientId("first-fixture"), "S1111111A",
            new PatientName("Lim Mei Ling", null, List.of()),
            AdministrativeGender.FEMALE, LocalDate.of(1970, 1, 2));

    private static final Patient SECOND = new Patient(
            new PatientId("second-fixture"), "S2222222A",
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
}
