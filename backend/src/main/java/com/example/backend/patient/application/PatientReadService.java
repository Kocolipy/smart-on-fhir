package com.example.backend.patient.application;

import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientStore;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reads a Patient by id. Read-only: nothing in the service changes a patient. No transaction: the
 * read is a call to the in-house patient source, not to the database (ADR 0014), and nothing is
 * cached, so every read is answered by the source.
 */
@Service
public class PatientReadService {

    private final PatientStore patients;

    public PatientReadService(PatientStore patients) {
        this.patients = patients;
    }

    public Optional<Patient> find(PatientId id) {
        return patients.findById(id);
    }
}
