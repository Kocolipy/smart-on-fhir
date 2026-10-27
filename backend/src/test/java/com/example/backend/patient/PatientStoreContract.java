package com.example.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientName;
import com.example.backend.patient.domain.PatientStore;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What every {@link PatientStore} does, whatever stores the
 * patients: the SQL Server adapter, and the in-memory store the seeding, read and controller unit
 * tests run against. Each runs it.
 *
 * <p>Each call is its own unit of work, as each seeding and each read is in production: the SQL
 * Server half runs every call in a transaction of its own. A refused insert therefore stores
 * nothing and leaves what was stored before it. What a refusal does to a transaction that carries
 * on past it — on SQL Server it can no longer commit, which is why {@code PatientSeedService} looks
 * before it inserts rather than catching — no in-memory store models, so
 * {@code SqlServerPatientStoreContractTests} asserts it against the adapter alone.
 *
 * <p>Every patient is under a fresh id and NRIC, so a case never meets a development fixture or
 * another class's row in the shared database.
 */
public abstract class PatientStoreContract {

    /** The patient port under test. */
    protected abstract PatientStore patients();

    @Test
    void an_inserted_patient_is_read_back_by_id_as_given() {
        Patient lim = patient(new PatientName("Lim Mei Ling", "Lim", List.of("Mei", "Ling")));

        patients().insert(lim);

        assertThat(patients().findById(lim.id())).contains(lim);
    }

    /** A name given by its text alone reads back with no family or given part. */
    @Test
    void a_name_given_by_its_text_alone_is_read_back_as_given() {
        Patient suriani = patient(new PatientName("Suriani", null, List.of()));

        patients().insert(suriani);

        assertThat(patients().findById(suriani.id())).contains(suriani);
    }

    @Test
    void an_inserted_patient_exists_by_its_id() {
        Patient lim = patient(new PatientName("Lim Mei Ling", null, List.of()));

        patients().insert(lim);

        assertThat(patients().existsById(lim.id())).isTrue();
    }

    @Test
    void an_id_naming_no_patient_finds_nothing() {
        assertThat(patients().findById(freshId())).isEmpty();
    }

    @Test
    void an_id_naming_no_patient_does_not_exist() {
        assertThat(patients().existsById(freshId())).isFalse();
    }

    /** A second patient under a stored id: refused, as the primary key refuses it. */
    @Test
    void a_second_patient_under_a_stored_id_is_refused() {
        Patient first = patient(new PatientName("First", null, List.of()));
        patients().insert(first);
        Patient sameId = second(first.id(), freshNric());

        assertThatThrownBy(() -> patients().insert(sameId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The patient a refused insert collided with is left exactly as it was. */
    @Test
    void a_refused_second_patient_under_a_stored_id_leaves_the_first() {
        Patient first = patient(new PatientName("First", null, List.of()));
        patients().insert(first);

        insertRefused(second(first.id(), freshNric()));

        assertThat(patients().findById(first.id())).contains(first);
    }

    /** An NRIC names one person: a second patient holding a stored one is refused. */
    @Test
    void a_second_patient_holding_a_stored_nric_is_refused() {
        Patient first = patient(new PatientName("First", null, List.of()));
        patients().insert(first);
        Patient sameNric = second(freshId(), first.nric());

        assertThatThrownBy(() -> patients().insert(sameNric))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A refused insert stores nothing: no patient is held under the refused one's id. */
    @Test
    void a_refused_second_patient_holding_a_stored_nric_is_not_stored() {
        Patient first = patient(new PatientName("First", null, List.of()));
        patients().insert(first);
        Patient sameNric = second(freshId(), first.nric());

        insertRefused(sameNric);

        assertThat(patients().existsById(sameNric.id())).isFalse();
    }

    private void insertRefused(Patient patient) {
        assertThatThrownBy(() -> patients().insert(patient))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A patient under a fresh id and NRIC. */
    protected static Patient patient(PatientName name) {
        return new Patient(freshId(), freshNric(), name, AdministrativeGender.FEMALE,
                LocalDate.of(1972, 2, 29));
    }

    /** Another patient, under {@code id} and holding {@code nric}, either of which may collide. */
    protected static Patient second(PatientId id, String nric) {
        return new Patient(id, nric, new PatientName("Second", null, List.of()),
                AdministrativeGender.MALE, LocalDate.of(1980, 1, 1));
    }

    /** A Patient's id no other row holds: a UUID's text is a valid FHIR id. */
    protected static PatientId freshId() {
        return new PatientId(UUID.randomUUID().toString());
    }

    /** An NRIC-shaped value no other row is likely to hold. */
    protected static String freshNric() {
        return "T" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000) + "Z";
    }
}
