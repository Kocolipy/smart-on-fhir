package com.example.backend.patient;

import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * A {@link PatientStore} in memory, for the tests above the persistence adapter. It refuses what
 * the table refuses that a caller could plausibly do — a second row under an id or an NRIC — so a
 * test that would fail against SQL Server fails here too.
 */
public final class InMemoryPatientStore implements PatientStore {

    private final Map<PatientId, Patient> patients = new LinkedHashMap<>();

    /** Stores {@code patient} as a test's given state. */
    public Patient given(Patient patient) {
        insert(patient);
        return patient;
    }

    /** Every stored patient, in insertion order. */
    public List<Patient> all() {
        return new ArrayList<>(patients.values());
    }

    @Override
    public Optional<Patient> findById(PatientId id) {
        return Optional.ofNullable(patients.get(id));
    }

    @Override
    public boolean existsById(PatientId id) {
        return patients.containsKey(id);
    }

    /**
     * Refuses with the exception the adapter's flush raises ({@code PatientStoreContract}). What
     * the refusal does to the rest of a transaction — on SQL Server it can no longer commit — is
     * not modelled here.
     */
    @Override
    public void insert(Patient patient) {
        if (patients.containsKey(patient.id())) {
            throw new DataIntegrityViolationException(
                    "pk_patients: a patient is already stored under " + patient.id());
        }
        if (patients.values().stream().anyMatch(stored -> stored.nric().equals(patient.nric()))) {
            throw new DataIntegrityViolationException(
                    "uq_patients_nric: a patient already holds that NRIC");
        }
        patients.put(patient.id(), patient);
    }
}
