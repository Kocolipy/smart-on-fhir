package com.example.backend.patient.infrastructure.http;

import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientName;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The in-house patient source's FHIR R4 {@code Patient}, as far as the service reads it: the
 * {@code id} and the four elements a Patient is copied from. Every other element the source sends
 * is never bound.
 *
 * <p>R4 and R5 agree on these elements (ADR 0014, "R5 on the wire"), so the mapping is a straight
 * copy: the {@code identifier} under the NRIC NamingSystem, the {@code name} whose {@code use} is
 * {@code official}, the {@code gender} and the {@code birthDate}.
 *
 * @param id         the resource's id, which must be the id it was read by
 * @param identifier every identifier the source holds; only the NRIC is copied
 * @param name       every name the source holds; only the official one is copied
 * @param gender     the administrative gender code
 * @param birthDate  the date of birth, which must be to the day
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record R4Patient(
        String id, List<Identifier> identifier, List<HumanName> name, String gender,
        String birthDate) {

    /** The official name's {@code use}. */
    private static final String OFFICIAL = "official";

    /**
     * The domain's Patient this resource is, read under {@code asked}.
     *
     * @throws PatientSourceException when the resource is another patient's, or lacks an element
     *                                a Patient must have, saying which and quoting no value
     */
    Patient toPatient(PatientId asked) {
        if (!asked.value().equals(id)) {
            throw new PatientSourceException("The patient source answered with another resource");
        }
        return new Patient(
                asked,
                nric(),
                officialName(),
                AdministrativeGender.fromCode(gender).orElseThrow(() ->
                        new PatientSourceException("The patient source's Patient has no gender")),
                dayOfBirth());
    }

    /** The date of birth, which must be there; a value not to the day fails to parse. */
    private LocalDate dayOfBirth() {
        if (birthDate == null) {
            throw new PatientSourceException("The patient source's Patient has no birth date");
        }
        return LocalDate.parse(birthDate);
    }

    private String nric() {
        return listed(identifier).stream()
                .filter(candidate -> Patient.NRIC_SYSTEM.equals(candidate.system()))
                .map(Identifier::value)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() ->
                        new PatientSourceException("The patient source's Patient has no NRIC"));
    }

    private PatientName officialName() {
        return listed(name).stream()
                .filter(candidate -> OFFICIAL.equals(candidate.use()))
                .findFirst()
                .map(official -> new PatientName(official.text(), official.family(),
                        official.given()))
                .orElseThrow(() -> new PatientSourceException(
                        "The patient source's Patient has no official name"));
    }

    private static <T> List<T> listed(List<T> elements) {
        return elements == null ? List.of() : elements;
    }

    /** An R4 {@code Identifier}: only its system and value are read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Identifier(String system, String value) {
    }

    /** An R4 {@code HumanName}: only its use, text, family and given names are read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HumanName(String use, String text, String family, List<String> given) {
    }
}
