package com.example.backend.auth.epic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The **Launch context** of an EHR launch (/CONTEXT.md): the patient and the encounter Epic's
 * token response names, each a FHIR id. The backend keeps neither — not in the session, not in
 * Redis, not with the Epic tokens — and only passes them on to the SPA, in the fragment of the
 * signed-in landing, {@code /#patient=<id>&encounter=<id>} (ADR 0012, D8). The fragment is never
 * sent to a server, so no access log of ours or a proxy's records it.
 *
 * <p>Each value is held to FHIR's id grammar, {@code [A-Za-z0-9\-.]{1,64}}, and a value outside
 * it — or one that is not a JSON string — is left out, the other kept. The grammar is
 * {@code patient.domain.PatientId}'s, which the patient read binds its path through; it is
 * mirrored here because {@code auth} may not reach the {@code patient} module (ArchitectureTest's
 * module graph), and {@code LaunchContextTests} holds the two to the same verdicts. Every value
 * that passes is made of letters, digits, {@code -} and {@code .}, so it goes into the fragment
 * as it is, with nothing to encode and no way to forge another member.
 */
public final class LaunchContext {

    /** The token-response member naming the patient in context (SMART App Launch). */
    static final String PATIENT = "patient";

    /** The token-response member naming the encounter in context (SMART App Launch). */
    static final String ENCOUNTER = "encounter";

    /** FHIR's id grammar, as {@code PatientId} holds it. */
    private static final Pattern FHIR_ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");

    /** The fragment of a landing with neither a patient nor an encounter to pass on. */
    private static final String BARE_MARKER = "launch";

    private final String patient;

    private final String encounter;

    private LaunchContext(String patient, String encounter) {
        this.patient = patient;
        this.encounter = encounter;
    }

    /**
     * The launch context {@code tokenResponse} — the members of Epic's token response beyond the
     * standard ones — names, each value outside FHIR's id grammar left out.
     */
    public static LaunchContext of(Map<String, Object> tokenResponse) {
        return new LaunchContext(fhirIdOf(tokenResponse.get(PATIENT)),
                fhirIdOf(tokenResponse.get(ENCOUNTER)));
    }

    /** No launch context at all. */
    public static LaunchContext none() {
        return new LaunchContext(null, null);
    }

    /**
     * The landing's fragment, without the {@code #}: {@code patient=<id>&encounter=<id>} with
     * whichever of the two is held, or the bare marker {@code launch} when neither is.
     */
    public String fragment() {
        List<String> members = new ArrayList<>(2);
        if (patient != null) {
            members.add(PATIENT + "=" + patient);
        }
        if (encounter != null) {
            members.add(ENCOUNTER + "=" + encounter);
        }
        return members.isEmpty() ? BARE_MARKER : String.join("&", members);
    }

    /** {@code value} when it is a FHIR id, otherwise {@code null}. */
    private static String fhirIdOf(Object value) {
        return value instanceof String id && FHIR_ID.matcher(id).matches() ? id : null;
    }
}
