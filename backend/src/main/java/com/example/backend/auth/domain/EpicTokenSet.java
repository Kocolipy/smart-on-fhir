package com.example.backend.auth.domain;

import java.io.Serial;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Epic tokens one successful Epic Login kept for the life of its session: the access token,
 * the instant it expires, the scope Epic granted it, the refresh token when Epic issued one, and
 * the raw {@code id_token} (ADR 0013, D29, superseding D8 for those three).
 *
 * <p>Nothing else from the token response is here: the patient and encounter launch context are
 * still dropped.
 *
 * <p>All three tokens are D22 values. The access and refresh tokens are bearer credentials at
 * Epic, and the {@code id_token} carries the clinician's identity claims, so none of them may
 * reach a log record, an audit event, an exception message or the browser. {@link #toString()}
 * therefore names each by its presence alone, and the type is reachable only through the
 * {@link EpicTokens} port, never from a web adapter's response.
 *
 * <p>Serializable because the session store keeps it as a session attribute, Java-serialized as
 * every other attribute is; every field is a {@code String}, an {@code Instant} or a sorted set
 * of strings, so it round-trips with nothing of this type's own behaviour in the stream.
 */
public final class EpicTokenSet implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String REDACTED = "<redacted>";

    private final String accessToken;

    private final Instant accessTokenExpiresAt;

    private final Set<String> scope;

    /** {@code null} when Epic issued none, which is the usual case (no {@code offline_access}). */
    private final String refreshToken;

    private final String idToken;

    private EpicTokenSet(String accessToken, Instant accessTokenExpiresAt, Set<String> scope,
            String refreshToken, String idToken) {
        this.accessToken = accessToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.scope = scope;
        this.refreshToken = refreshToken;
        this.idToken = idToken;
    }

    /**
     * The tokens of a token response received at {@code receivedAt}.
     *
     * @param accessToken  the access token's value
     * @param lifetime     how long the access token lives from its receipt: Epic's
     *                     {@code expires_in}
     * @param receivedAt   when the token response was received, from the injected clock, which
     *                     makes {@code expires_in} an absolute expiry
     * @param scope        the scope Epic granted the access token
     * @param refreshToken the refresh token's value, empty when Epic issued none: an
     *                     {@code Optional} rather than a nullable {@code String}, so it cannot
     *                     be swapped with the {@code id_token} beside it and still compile
     * @param idToken      the raw, validated {@code id_token}
     */
    public static EpicTokenSet issued(String accessToken, Duration lifetime, Instant receivedAt,
            Set<String> scope, Optional<String> refreshToken, String idToken) {
        // The messages name the missing argument, never a value: a value here is a D22 one.
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshToken, "refreshToken");
        Objects.requireNonNull(idToken, "idToken");
        // Held as a nullable String, not the Optional: the set is Java-serialized into the
        // session store, and Optional is not Serializable.
        return new EpicTokenSet(accessToken, receivedAt.plus(lifetime),
                Collections.unmodifiableSortedSet(new TreeSet<>(scope)), refreshToken.orElse(null),
                idToken);
    }

    /** The access token's value, for presenting to Epic's FHIR API as a bearer token. */
    public String accessToken() {
        return accessToken;
    }

    /** The instant the access token stops being valid at Epic. */
    public Instant accessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    /**
     * Whether the access token has expired by {@code clock}: from its expiry instant on, the token
     * is no longer one to present. An expired access token leaves the rest of the set as it was —
     * the refresh token and the {@code id_token} live as long as the session.
     */
    public boolean isExpired(Clock clock) {
        return !clock.instant().isBefore(accessTokenExpiresAt);
    }

    /** The scope Epic granted the access token. */
    public Set<String> scope() {
        return scope;
    }

    /** The refresh token, when Epic issued one. Nothing in this service refreshes yet. */
    public Optional<String> refreshToken() {
        return Optional.ofNullable(refreshToken);
    }

    /** The raw {@code id_token} the Login was validated from. */
    public String idToken() {
        return idToken;
    }

    /** Names each token by its presence alone (D22); the expiry and the scope are not secret. */
    @Override
    public String toString() {
        return "EpicTokenSet[accessToken=" + REDACTED
                + ", accessTokenExpiresAt=" + accessTokenExpiresAt
                + ", scope=" + scope
                + ", refreshToken=" + (refreshToken == null ? "<none>" : REDACTED)
                + ", idToken=" + REDACTED + "]";
    }
}
