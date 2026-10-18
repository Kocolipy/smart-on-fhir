package com.example.backend.patient.domain;

import java.util.List;
import java.util.Objects;

/**
 * A Patient's official name. {@code text} is required and authoritative: it is the name as the
 * person writes it. The split into {@code family} and {@code given} names is optional, because
 * many Singapore names do not split cleanly into the two.
 *
 * @param text   the whole name, never blank
 * @param family the family name, or {@code null} when the name is not split
 * @param given  the given names in order; empty when the name is not split
 */
public record PatientName(String text, String family, List<String> given) {

    public PatientName {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("A patient's name text is required");
        }
        given = given == null ? List.of() : List.copyOf(given);
    }
}
