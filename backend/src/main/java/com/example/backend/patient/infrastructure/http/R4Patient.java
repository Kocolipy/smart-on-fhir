package com.example.backend.patient.infrastructure.http;

import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientName;
import com.example.backend.patient.infrastructure.http.PatientSourceException.Failure;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
     * @throws PatientSourceException when the resource is another patient's, or breaks a rule a
     *                                Patient must keep, naming which and quoting no value
     */
    Patient toPatient(PatientId asked) {
        if (!asked.value().equals(id)) {
            throw PatientSourceException.failed(Failure.ANOTHER_RESOURCE);
        }
        return new Patient(
                asked,
                nric(),
                officialName(),
                AdministrativeGender.fromCode(gender).orElseThrow(() ->
                        PatientSourceException.failed(Failure.NO_GENDER)),
                dayOfBirth());
    }

    /** The date of birth, which must be there, and to the day. */
    private LocalDate dayOfBirth() {
        if (birthDate == null) {
            throw PatientSourceException.failed(Failure.NO_BIRTH_DATE);
        }
        try {
            return LocalDate.parse(birthDate);
        } catch (DateTimeParseException notToTheDay) {
            throw PatientSourceException.failed(Failure.BIRTH_DATE_NOT_TO_THE_DAY);
        }
    }

    private String nric() {
        return listed(identifier).stream()
                .filter(candidate -> Patient.NRIC_SYSTEM.equals(candidate.system()))
                .map(Identifier::value)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> PatientSourceException.failed(Failure.NO_NRIC));
    }

    /**
     * The official name, which must have its text: the text is the name as the person writes it,
     * and is never rebuilt from the family and given names, which many names do not split into.
     */
    private PatientName officialName() {
        HumanName official = listed(name).stream()
                .filter(candidate -> OFFICIAL.equals(candidate.use()))
                .findFirst()
                .orElseThrow(() -> PatientSourceException.failed(Failure.NO_OFFICIAL_NAME));
        if (official.text() == null || official.text().isBlank()) {
            throw PatientSourceException.failed(Failure.NO_NAME_TEXT);
        }
        return new PatientName(official.text(), official.family(), official.given());
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
