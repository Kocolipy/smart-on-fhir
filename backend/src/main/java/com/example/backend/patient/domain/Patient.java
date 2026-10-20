package com.example.backend.patient.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A Patient: the person care is about, as the service holds them (/docs/domain/patient.md).
 *
 * <p>Pure domain, with no FHIR wire shape: the web adapter renders it as an R5 {@code Patient}.
 * There is no age here. Age is derived from {@code birthDate} where it is shown, never stored.
 *
 * <p>Patient data is Restricted, Sensitive Normal, and a log names a patient by its id alone
 * (/docs/domain/patient.md, "Logging"). So {@link #toString()} prints the id and nothing else: a
 * record's generated one prints every field, and would put the NRIC, the name and the date of
 * birth into any record, message or exception that happened to print a Patient.
 *
 * @param id        the patient's id: its EHR's FHIR id, and its FHIR resource id here
 * @param nric      the NRIC, its one identifier; dummy values only today, their shape unchecked
 * @param name      the official name
 * @param gender    the administrative gender
 * @param birthDate the date of birth
 */
public record Patient(
        PatientId id, String nric, PatientName name, AdministrativeGender gender, LocalDate birthDate) {

    public Patient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nric, "nric");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(gender, "gender");
        Objects.requireNonNull(birthDate, "birthDate");
    }

    @Override
    public String toString() {
        return "Patient[id=" + id + "]";
    }
}
