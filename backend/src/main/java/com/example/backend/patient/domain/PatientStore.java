package com.example.backend.patient.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for patient storage. Owned by the domain and implemented by an infrastructure
 * adapter, so the dependency points inward — and so a future adapter backed by an EHR's FHIR
 * server can stand in for the table one (ADR 0014).
 */
public interface PatientStore {

    Optional<Patient> findById(UUID id);

    /** Whether a patient is stored under {@code id}. */
    boolean existsById(UUID id);

    /** Stores a patient that is not stored yet. The development fixtures' seeder is the only caller. */
    void insert(Patient patient);
}
