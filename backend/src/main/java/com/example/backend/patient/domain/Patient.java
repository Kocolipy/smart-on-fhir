package com.example.backend.patient.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A Patient: the person care is about, as the service holds them (/docs/domain/patient.md).
 *
 * <p>Pure domain, with no FHIR wire shape: the web adapter renders it as an R5 {@code Patient}.
 * There is no age here. Age is derived from {@code birthDate} where it is shown, never stored.
 *
 * @param id        the patient's stable id, also its FHIR resource id
 * @param nric      the NRIC, its one identifier; dummy values only today, their shape unchecked
 * @param name      the official name
 * @param gender    the administrative gender
 * @param birthDate the date of birth
 */
public record Patient(
        UUID id, String nric, PatientName name, AdministrativeGender gender, LocalDate birthDate) {

    public Patient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nric, "nric");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(gender, "gender");
        Objects.requireNonNull(birthDate, "birthDate");
    }
}
