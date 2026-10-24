package com.example.backend.auth.epic;

/**
 * The token response's {@code hub.url} and {@code hub.topic} as Epic sent them — any JSON value,
 * or {@code null} for a member it left out — carried on the callback's {@link EpicIdentity} to the
 * success handler, which keeps them on the signed-in session only once they pass
 * {@code FhircastHub.of} (ADR 0016). Nothing here checks them.
 *
 * @param url   the {@code hub.url} member
 * @param topic the {@code hub.topic} member
 */
public record FhircastHubMembers(Object url, Object topic) {

    /** A token response naming no hub. */
    public static FhircastHubMembers none() {
        return new FhircastHubMembers(null, null);
    }
}
