package com.example.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.patient.application.DevPatientFixtures;
import com.example.backend.patient.application.PatientReadService;
import com.example.backend.patient.application.PatientSeedService;
import com.example.backend.patient.application.PatientSeedService.SeedCounts;
import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientName;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The {@code patients} table as the service reaches it: a patient seeded through the seeder reads
 * back unchanged through the read service, the schema refuses what a Patient must not be, and the
 * app login may read and insert but neither change nor remove a row.
 *
 * <p>Every row here is the test's own, under a fresh id and NRIC, whatever the development-fixture
 * flag says; the owner login removes them afterwards, as the app login cannot.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class PatientStorageIntegrationTests {

    private static final String INSERT = "INSERT INTO patients"
            + " (id, nric, name_text, name_family, name_given, gender, birth_date)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    @Autowired
    private PatientSeedService seeding;

    @Autowired
    private PatientReadService reads;

    /** The context's own connections: exactly the app login's privileges. */
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseLogins logins;

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void removeWhatThisTestCreated() {
        for (String id : created) {
            logins.owner().jdbc().update("DELETE FROM patients WHERE id = ?", id);
        }
    }

    /** A row id no other row holds, for a raw insert: a UUID's text is a valid FHIR id. */
    private String freshRowId() {
        String id = UUID.randomUUID().toString();
        created.add(id);
        return id;
    }

    /** A Patient's id no other row holds. */
    private PatientId freshId() {
        return new PatientId(freshRowId());
    }

    private static String freshNric() {
        return "T" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000) + "Z";
    }

    private Patient patient(PatientName name) {
        return new Patient(freshId(), freshNric(), name, AdministrativeGender.FEMALE,
                LocalDate.of(1972, 2, 29));
    }

    @Test
    void aSeededPatientReadsBackAsItWasSeeded() {
        Patient lim = patient(new PatientName("Lim Mei Ling", "Lim", List.of("Mei", "Ling")));

        seeding.seedDevFixtures(List.of(lim));

        assertThat(reads.find(lim.id())).contains(lim);
    }

    @Test
    void aNameGivenByItsTextAloneReadsBackWithNoFamilyOrGivenPart() {
        Patient suriani = patient(new PatientName("Suriani", null, List.of()));

        seeding.seedDevFixtures(List.of(suriani));

        assertThat(reads.find(suriani.id())).contains(suriani);
        assertThat(jdbc.queryForList(
                "SELECT name_family, name_given FROM patients WHERE id = ?", suriani.id().value()))
                .singleElement()
                .satisfies(row -> assertThat(row.values()).containsOnlyNulls());
    }

    @Test
    void anIdNoPatientIsHeldUnderReadsAsAbsent() {
        assertThat(reads.find(new PatientId("no-such-patient-" + UUID.randomUUID()))).isEmpty();
    }

    @Test
    void seedingTwiceLeavesOneRowPerFixture() {
        Patient first = patient(new PatientName("First Fixture", null, List.of()));
        Patient second = patient(new PatientName("Second Fixture", null, List.of()));

        SeedCounts once = seeding.seedDevFixtures(List.of(first, second));
        SeedCounts twice = seeding.seedDevFixtures(List.of(first, second));

        assertThat(once).isEqualTo(new SeedCounts(2, 0));
        assertThat(twice).isEqualTo(new SeedCounts(0, 2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM patients WHERE id IN (?, ?)",
                Integer.class, first.id().value(), second.id().value())).isEqualTo(2);
    }

    /**
     * Each development fixture's name, gender and birth date under a fresh id and NRIC, so the
     * fixtures' own shapes make the SQL round-trip without touching their fixed ids.
     */
    private List<Patient> freshCopiesOfTheFixtures() {
        return DevPatientFixtures.ALL.stream()
                .map(fixture -> new Patient(freshId(), freshNric(), fixture.name(), fixture.gender(),
                        fixture.birthDate()))
                .toList();
    }

    @Test
    void theFixturesSeedOnceAndEachReadsBackAsItWasSeeded() {
        List<Patient> fixtures = freshCopiesOfTheFixtures();

        SeedCounts once = seeding.seedDevFixtures(fixtures);
        SeedCounts twice = seeding.seedDevFixtures(fixtures);

        assertThat(once).isEqualTo(new SeedCounts(4, 0));
        assertThat(twice).isEqualTo(new SeedCounts(0, 4));
        assertThat(fixtures).allSatisfy(fixture ->
                assertThat(reads.find(fixture.id())).contains(fixture));
    }

    @Test
    void theFixturesGivenNamesAreStoredAsOneSpaceSeparatedColumn() {
        List<Patient> fixtures = freshCopiesOfTheFixtures();

        seeding.seedDevFixtures(fixtures);

        Map<PatientId, String> storedGiven = new HashMap<>();
        Map<PatientId, String> expected = new HashMap<>();
        for (Patient fixture : fixtures) {
            storedGiven.put(fixture.id(), jdbc.queryForObject(
                    "SELECT name_given FROM patients WHERE id = ?", String.class, fixture.id().value()));
            List<String> given = fixture.name().given();
            expected.put(fixture.id(), given.isEmpty() ? null : String.join(" ", given));
        }
        assertThat(storedGiven).isEqualTo(expected);
    }

    @Test
    void theSchemaRefusesASecondPatientWithTheSameNric() {
        String nric = freshNric();
        jdbc.update(INSERT, freshRowId(), nric, "One", null, null, "male", "1980-01-01");

        assertThatThrownBy(() ->
                jdbc.update(INSERT, freshRowId(), nric, "Two", null, null, "male", "1980-01-01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_patients_nric");
    }

    @Test
    void theSchemaRefusesAPatientWithNoNameText() {
        assertThatThrownBy(() ->
                jdbc.update(INSERT, freshRowId(), freshNric(), null, "Tan", "Wei", "male", "1980-01-01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("name_text");
    }

    @Test
    void theSchemaRefusesAPatientWithNoGender() {
        assertThatThrownBy(() ->
                jdbc.update(INSERT, freshRowId(), freshNric(), "No Gender", null, null, null, "1980-01-01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("gender");
    }

    @Test
    void theSchemaRefusesAPatientWithNoBirthDate() {
        assertThatThrownBy(() ->
                jdbc.update(INSERT, freshRowId(), freshNric(), "No Birth Date", null, null, "male", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("birth_date");
    }

    @Test
    void theSchemaRefusesAGenderOutsideTheFourCodes() {
        assertThatThrownBy(() ->
                jdbc.update(INSERT, freshRowId(), freshNric(), "Upper Case", null, null, "Male", "1980-01-01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_patients_gender");
    }

    @Test
    void theAppLoginCannotChangeAPatient() {
        Patient stored = patient(new PatientName("Unchangeable", null, List.of()));
        seeding.seedDevFixtures(List.of(stored));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE patients SET name_text = 'Changed' WHERE id = ?", stored.id().value()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UPDATE permission was denied");
    }

    @Test
    void theAppLoginCannotRemoveAPatient() {
        Patient stored = patient(new PatientName("Irremovable", null, List.of()));
        seeding.seedDevFixtures(List.of(stored));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM patients WHERE id = ?", stored.id().value()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("DELETE permission was denied");
    }
}
