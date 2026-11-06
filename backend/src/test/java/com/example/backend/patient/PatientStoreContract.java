package com.example.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientName;
import com.example.backend.patient.domain.PatientStore;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What every {@link PatientStore} reads, whatever it reads from, given the patient source's four
 * development fixtures ({@code patient-source-mock/mappings}, /docs/domain/patient.md): each
 * fixture by its id, exactly as the domain holds it, and nothing under an id no fixture holds.
 *
 * <p>The expected Patients are written out here, not derived from the stub files, so a stub that
 * drifted from the fixture table in /docs/domain/patient.md fails here.
 */
public abstract class PatientStoreContract {

    /** The patient port under test, reading the four fixtures. */
    protected abstract PatientStore patients();

    @Test
    void the_chinese_fixture_is_read_with_its_family_name_first() {
        assertThat(patients().findById(new PatientId("eq081-VQEgP8drUUqCWzHfw3"))).contains(
                new Patient(
                        new PatientId("eq081-VQEgP8drUUqCWzHfw3"),
                        "S1234567A",
                        new PatientName("Tan Wei Ming", "Tan", List.of("Wei", "Ming")),
                        AdministrativeGender.MALE,
                        LocalDate.of(1985, 3, 12)));
    }

    @Test
    void the_malay_fixture_is_read_with_its_name_left_unsplit() {
        assertThat(patients().findById(new PatientId("erXuFYUfucBZaryVksYEcMg3"))).contains(
                new Patient(
                        new PatientId("erXuFYUfucBZaryVksYEcMg3"),
                        "S9212345A",
                        new PatientName("Siti Nurhaliza binte Ahmad", null, List.of()),
                        AdministrativeGender.FEMALE,
                        LocalDate.of(1992, 8, 21)));
    }

    @Test
    void the_indian_fixture_is_read_as_a_single_given_name_with_no_family_name() {
        assertThat(patients().findById(new PatientId("e0w0LEDCYtfckT6N.CkJKCw3"))).contains(
                new Patient(
                        new PatientId("e0w0LEDCYtfckT6N.CkJKCw3"),
                        "T0312345C",
                        new PatientName("Kumaravel", null, List.of("Kumaravel")),
                        AdministrativeGender.UNKNOWN,
                        LocalDate.of(2003, 11, 5)));
    }

    @Test
    void the_eurasian_fixture_is_read_with_given_names_first_and_a_multi_word_family_name() {
        assertThat(patients().findById(new PatientId("egqBHVfQlt4Bw3XGXoxVxHg3"))).contains(
                new Patient(
                        new PatientId("egqBHVfQlt4Bw3XGXoxVxHg3"),
                        "S7812345B",
                        new PatientName("Marcus Anthony de Souza", "de Souza",
                                List.of("Marcus", "Anthony")),
                        AdministrativeGender.OTHER,
                        LocalDate.of(1978, 6, 30)));
    }

    /** {@code not-a-uuid} is a FHIR id like any other, and one no fixture is held under. */
    @Test
    void a_well_formed_id_no_patient_is_held_under_finds_nothing() {
        assertThat(patients().findById(new PatientId("not-a-uuid"))).isEmpty();
    }
}
