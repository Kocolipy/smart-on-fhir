package com.example.backend.patient.domain;

import java.util.Arrays;
import java.util.Optional;

/** FHIR's administrative gender: the four codes a Patient's {@code gender} takes. */
public enum AdministrativeGender {
    MALE("male"),
    FEMALE("female"),
    OTHER("other"),
    UNKNOWN("unknown");

    private final String code;

    AdministrativeGender(String code) {
        this.code = code;
    }

    /** The code as FHIR spells it, and as the {@code patients.gender} column stores it. */
    public String code() {
        return code;
    }

    /** The gender {@code code} spells, if it is one of the four. */
    public static Optional<AdministrativeGender> fromCode(String code) {
        return Arrays.stream(values()).filter(gender -> gender.code.equals(code)).findFirst();
    }
}
