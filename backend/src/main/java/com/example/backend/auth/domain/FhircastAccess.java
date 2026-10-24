package com.example.backend.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * What a FHIRcast release hands the SPA (ADR 0016): the Epic session's SMART access token, its
 * expiry and granted scope, and the {@link FhircastHub} it connects to — and nothing else Epic
 * issued. There is no field the {@code id_token} or a refresh token could go in, so a web adapter
 * handed this cannot render either.
 *
 * <p>The access token is a D22 value: {@link #toString()} names it by its presence alone, and
 * {@code ArchitectureTest} holds this type to the one REST controller that releases it.
 *
 * @param accessToken the SMART access token's value
 * @param expiresAt   the instant it stops being valid at Epic
 * @param scope       the scope Epic granted it
 * @param hub         the hub the token response named
 */
public record FhircastAccess(String accessToken, Instant expiresAt, Set<String> scope,
        FhircastHub hub) {

    public FhircastAccess {
        // The message names the missing argument, never a value: a value here is a D22 one.
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(hub, "hub");
        scope = Set.copyOf(scope);
    }

    /** The access token of {@code tokens}, with its expiry and scope, for {@code hub}. */
    public static FhircastAccess of(EpicTokenSet tokens, FhircastHub hub) {
        return new FhircastAccess(tokens.accessToken(), tokens.accessTokenExpiresAt(),
                tokens.scope(), hub);
    }

    /** Names the access token by its presence alone (D22). */
    @Override
    public String toString() {
        return "FhircastAccess[accessToken=<redacted>, expiresAt=" + expiresAt + ", scope=" + scope
                + ", hub=" + hub + "]";
    }
}
