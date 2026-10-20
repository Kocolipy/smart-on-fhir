package com.example.backend.patient.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A Patient's id: its EHR's FHIR id, so the patient an EHR launch names is read by that id
 * directly (ADR 0014, /docs/domain/patient.md). It takes exactly FHIR's id grammar,
 * {@code [A-Za-z0-9\-.]{1,64}}, and nothing else.
 *
 * <p>An id outside the grammar is refused with a fixed message that quotes none of it: the value
 * arrives from a request path, and may carry markup or a line break. {@code GET
 * /api/patient/{id}} binds its path segment through the constructor, so the refusal is the
 * {@code 400} of a parameter that cannot be bound.
 *
 * @param value the FHIR id
 */
public record PatientId(String value) {

    private static final Pattern FHIR_ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");

    public PatientId {
        Objects.requireNonNull(value, "value");
        if (!FHIR_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("A patient id must match the FHIR id grammar");
        }
    }

    /** The id itself, as it appears in a FHIR resource and a request path. */
    @Override
    public String toString() {
        return value;
    }
}
