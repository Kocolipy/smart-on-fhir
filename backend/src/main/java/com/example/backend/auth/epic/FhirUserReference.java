package com.example.backend.auth.epic;

import java.net.URI;
import java.util.Optional;

/**
 * Reads the Practitioner ID out of a validated {@code id_token}'s {@code fhirUser} claim (flow
 * step 5): the claim must be exactly {@code {fhirBase}/Practitioner/{id}}, under this
 * deployment's one FHIR base (D4), with a non-blank {@code id} and nothing after it.
 *
 * <p>Compared as an exact string, never normalized: the base is the configured
 * {@code APP_EPIC_FHIR_BASE} character for character, and the resource type is
 * {@code Practitioner} exactly. A launcher or environment whose claim has another shape is
 * absorbed by configuration, never by loosening this — a Patient, a RelatedPerson, another
 * organisation's server, a versioned reference or a query all name nobody here.
 *
 * <p>The one exception is the dev profile's, decided where D21's {@code http} allowance is
 * ({@link EpicLoginSettings#relativeFhirUserAllowed()}): the local SMART Health IT launcher
 * issues the relative {@code Practitioner/{id}}, which no configuration can make absolute, so in
 * that profile alone the relative form is accepted too, under the same rules — the type exactly
 * {@code Practitioner}, a non-blank {@code id}, and nothing after it.
 *
 * <p>The ID it yields is what links the clinician to a User (D2): the User whose stored
 * {@code userName} equals it exactly (D3). Neither the claim nor the ID is ever logged or
 * audited (ADR 0013, "Audit").
 */
public final class FhirUserReference {

    /** The one resource type a clinician's {@code fhirUser} may name, as a relative reference. */
    private static final String PRACTITIONER = "Practitioner/";

    private FhirUserReference() {
    }

    /**
     * The Practitioner ID {@code fhirUser} names under {@code fhirBase}, or empty when the claim
     * is absent or has any other form.
     *
     * @param relativeAllowed whether the relative {@code Practitioner/{id}} is accepted as well,
     *                        which is so in the dev profile only
     */
    public static Optional<String> practitionerId(
            String fhirUser, URI fhirBase, boolean relativeAllowed) {
        if (fhirUser == null) {
            return Optional.empty();
        }
        String absolute = fhirBase.toString() + "/" + PRACTITIONER;
        if (fhirUser.startsWith(absolute)) {
            return idAfter(fhirUser, absolute);
        }
        if (relativeAllowed && fhirUser.startsWith(PRACTITIONER)) {
            return idAfter(fhirUser, PRACTITIONER);
        }
        return Optional.empty();
    }

    /** What follows {@code prefix}, if it is a non-blank id with nothing after it. */
    private static Optional<String> idAfter(String fhirUser, String prefix) {
        String id = fhirUser.substring(prefix.length());
        if (id.isBlank() || id.chars().anyMatch(FhirUserReference::endsTheId)) {
            return Optional.empty();
        }
        return Optional.of(id);
    }

    /** A character that would start another segment, a query or a fragment after the id. */
    private static boolean endsTheId(int character) {
        return character == '/' || character == '?' || character == '#';
    }
}
