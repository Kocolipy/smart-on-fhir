package com.example.backend.patient;

import com.example.backend.patient.domain.PatientStore;

/** The in-memory patient store holds to the rules the SQL Server adapter does. */
class InMemoryPatientStoreContractTests extends PatientStoreContract {

    private final InMemoryPatientStore patients = new InMemoryPatientStore();

    @Override
    protected PatientStore patients() {
        return patients;
    }
}
