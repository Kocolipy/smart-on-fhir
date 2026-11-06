package com.example.backend.patient.domain;

import java.util.Optional;

/**
 * Outbound port for reading a Patient. Owned by the domain and implemented by an infrastructure
 * adapter, so the dependency points inward: today the HTTP adapter over the in-house patient
 * source (ADR 0014), which the controller, the wire and the SPA do not know of.
 *
 * <p>Read-only: nothing in the service writes a Patient.
 */
public interface PatientStore {

    /** The Patient held under {@code id}, or none when the source holds no patient under it. */
    Optional<Patient> findById(PatientId id);
}
