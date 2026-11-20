package com.example.backend.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * What a FHIRcast release hands the SPA (ADR 0016): the {@link FhircastHub} the session kept, and,
 * while it is unexpired, the Epic session's SMART access token with its expiry and granted scope —
 * and nothing else Epic issued. There is no field the {@code id_token} or a refresh token could
 * go in, so a web adapter handed this cannot render either.
 *
 * <p>The access token and its expiry are present together or not at all, and with no token there
 * is no scope: {@link #hubAlone} is the release once the token has expired.
 *
 * <p>The access token is a D22 value: {@link #toString()} names it by its presence alone, and
 * {@code ArchitectureTest} holds this type to the one REST controller that releases it.
 *
 * @param accessToken the SMART access token's value, or {@code null} for the hub alone
 * @param expiresAt   the instant it stops being valid at Epic, or {@code null} with no token
 * @param scope       the scope Epic granted it; empty with no token
 * @param hub         the hub the token response named
 */
public record FhircastAccess(String accessToken, Instant expiresAt, Set<String> scope,
        FhircastHub hub) {

    public FhircastAccess {
        // Each message names the missing argument, never a value: a value here is a D22 one.
        if (accessToken != null || expiresAt != null) {
            Objects.requireNonNull(accessToken, "accessToken");
            Objects.requireNonNull(expiresAt, "expiresAt");
        } else if (!scope.isEmpty()) {
            throw new IllegalArgumentException("scope");
        }
        Objects.requireNonNull(hub, "hub");
        scope = Set.copyOf(scope);
    }

    /** The access token of {@code tokens}, with its expiry and scope, for {@code hub}. */
    public static FhircastAccess of(EpicTokenSet tokens, FhircastHub hub) {
        return new FhircastAccess(tokens.accessToken(), tokens.accessTokenExpiresAt(),
                tokens.scope(), hub);
    }

    /** {@code hub} with no access token: what is released once the token has expired. */
    public static FhircastAccess hubAlone(FhircastHub hub) {
        return new FhircastAccess(null, null, Set.of(), hub);
    }

    /** Whether this release carries the SMART access token. */
    public boolean carriesToken() {
        return accessToken != null;
    }

    /** Names the access token by its presence alone (D22). */
    @Override
    public String toString() {
        return "FhircastAccess[accessToken=" + (carriesToken() ? "<redacted>" : "<none>")
                + ", expiresAt=" + expiresAt + ", scope=" + scope + ", hub=" + hub + "]";
    }
}
