package com.example.backend.patient.application;

import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientStore;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a Patient by id. Read-only: nothing in the service changes a patient. */
@Service
public class PatientReadService {

    private final PatientStore patients;

    public PatientReadService(PatientStore patients) {
        this.patients = patients;
    }

    @Transactional(readOnly = true)
    public Optional<Patient> find(PatientId id) {
        return patients.findById(id);
    }
}
