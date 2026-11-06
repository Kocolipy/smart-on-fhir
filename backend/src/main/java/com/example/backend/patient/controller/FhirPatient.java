package com.example.backend.patient.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientName;
import java.util.List;

/**
 * A Patient as a FHIR R5 {@code Patient} resource, in the JSON shape Jackson writes this record
 * in: base R5, no {@code meta} and so no {@code meta.profile}, and no element beyond the five the
 * domain holds (/docs/domain/patient.md). The service renders FHIR itself from its own records; no
 * FHIR library is on the runtime classpath.
 *
 * @param resourceType always {@code Patient}
 * @param id           the patient's id, its EHR's FHIR id
 * @param identifier   exactly one: the NRIC, under Synapxe's NRIC NamingSystem
 *                     ({@link Patient#NRIC_SYSTEM})
 * @param name         exactly one: the official name
 * @param gender       the administrative gender code
 * @param birthDate    the date of birth, {@code YYYY-MM-DD}
 */
public record FhirPatient(
        String resourceType,
        String id,
        List<Identifier> identifier,
        List<HumanName> name,
        String gender,
        String birthDate) {

    /** The resource {@code patient} renders as. */
    public static FhirPatient of(Patient patient) {
        return new FhirPatient(
                "Patient",
                patient.id().value(),
                List.of(new Identifier(Patient.NRIC_SYSTEM, patient.nric())),
                List.of(HumanName.official(patient.name())),
                patient.gender().code(),
                patient.birthDate().toString());
    }

    /** A FHIR {@code Identifier}: a value and the system it is unique within. */
    public record Identifier(String system, String value) {
    }

    /**
     * A FHIR {@code HumanName}. {@code text} is always there and authoritative; {@code family}
     * and {@code given} are left out, not written empty, when the name is not split.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record HumanName(String use, String text, String family, List<String> given) {

        static HumanName official(PatientName name) {
            return new HumanName("official", name.text(), name.family(), name.given());
        }
    }
}
